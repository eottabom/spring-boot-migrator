package com.eottabom.migration.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.misc.TextFiles;
import com.eottabom.migration.pipeline.Resumption.Resumed;
import com.eottabom.migration.pipeline.step.BaselineBuild;
import com.eottabom.migration.plan.MigrationPlan;
import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.recipe.AssembledRecipe;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.RunState;
import org.gradle.api.GradleException;

/**
 * 한 번의 migrationRun. 재개 판단, 시작 준비(의존성 버전, detect, 원본 빌드) 뒤에 stage 마다 step 을 돈다
 * ({@link StageRunner}).
 *
 * <pre>
 * stage 마다
 *   Rewrite      레시피 조립, rewriteRun
 *   Gate         compile
 *   Deprecation  컴파일 경고의 deprecated API 를 대체 레시피로 바꾸고 다시 compile
 *   Gate         build (전체 테스트 + 패키징)
 *   Assess       결과 모델
 *   Record       result.md, result.json, patch, result.html
 *   Commit       --commit 이고 게이트를 통과했을 때
 * </pre>
 *
 * 게이트에서 멈추면 run-state.json 에 기록하고, 같은 명령을 다시 실행하면 멈춘 stage 부터 이어서 한다
 * ({@link Resumption}).
 */
final class MigrationPipeline {

	/** 실행을 시작한 작업 트리를 잡아 두는 ref (누적 patch 의 기준) */
	static final String START_REF = "refs/spring-boot-migrator/start";

	private final RunSession session;

	private final StageRunner stages;

	MigrationPipeline(MigrationRunner runner, MigrationConfig config, MigrationWorkspace ws) {
		this.session = new RunSession(runner, config, ws);
		this.stages = new StageRunner(this.session);
	}

	void run() {
		MigrationConfig config = this.session.config();
		RunnerConsole console = this.session.console();
		Resumed resumed = config.preview() ? Resumed.NONE : new Resumption(this.session, this.stages).resume();

		ProjectState project = this.session.runner()
			.withResolvedBootVersion(this.session.components().inspector().inspect(this.session.projectDir()),
					this.session.gradle(), this.session.ws().start());
		this.session.isGit(project.gitRoot());
		MigrationPlan plan = this.session.runner().planOrFail(project, config);
		String summary = config.summary(plan.targetJava());
		console.heading("프로젝트 : " + this.session.projectDir());
		console.line("   현재     : Boot {} / Gradle {} / JAVA_HOME={}", project.bootVersion(),
				RunnerConsole.orUnknown(project.gradleVersion()),
				RunnerConsole.orDefault(this.session.gradle().javaHome()));
		console.line("   목표     : Boot {}  ({})", plan.targetBoot(), summary);
		console.projectRecipes(this.session.projectDir(), this.session.projectRecipes());
		if (project.gitRoot()) {
			// 결과 디렉토리와 조립한 레시피는 git 에 올리지 않는다 (patch 스냅샷과 커밋 대상에서 제외)
			this.session.git().exclude(MigrationWorkspace.DIR_NAME + "/");
			this.session.git().exclude(AssembledRecipe.RELATIVE_PATH);
		}
		checkWorkingTree(project, resumed.active());
		if (plan.isEmpty()) {
			console.heading("이미 Boot " + project.bootVersion() + " (목표 " + plan.targetBoot() + " 이상)예요.");
			return;
		}
		if (!resumed.active() && !config.preview()) {
			start(project);
		}
		console.line("   Java     : {} -> {}", RunnerConsole.orUnknown(project.lowestDeclaredJava()),
				(plan.targetJava() == null) ? "유지" : plan.targetJava());
		console.line("   stage    : {}", plan.stageNames());
		console.targetLine(plan);
		console.notes(plan);
		this.session.history().header(project, plan, summary, resumed.note());
		prepare(resumed.active());
		this.session.history().stageTable();

		// stage 번호는 지난 기록 뒤에 이어서 붙인다 (재개 시에는 다시 시도하는 stage 번호부터)
		int lastCompletedOrder = (resumed.lastCompletedOrder() != null) ? resumed.lastCompletedOrder()
				: this.session.ws().stageTags().size();
		if (config.preview()) {
			preview(plan, lastCompletedOrder);
			return;
		}
		int order = lastCompletedOrder;
		for (Stage stage : plan.stages()) {
			order++;
			this.stages.run(stage, stage.tag(order));
		}
		finish(project, plan);
	}

	/** 새로 시작할 때는 자동 변경이 기존 변경과 섞이지 않도록 깨끗한 작업 트리를 요구한다 */
	private void checkWorkingTree(ProjectState project, boolean resumed) {
		MigrationConfig config = this.session.config();
		if (config.commit() && !project.gitRoot()) {
			throw new GradleException("--commit 은 git 저장소에서만 쓸 수 있어요");
		}
		if (!project.dirty() || resumed || config.preview()) {
			return;
		}
		if (config.commit()) {
			throw new GradleException("--commit 은 작업 트리가 깨끗해야 해요 (기존 변경이 커밋에 섞여요)");
		}
		if (!config.allowDirty()) {
			throw new GradleException("작업 트리에 커밋되지 않은 변경이 있어요. 커밋하거나 --allow-dirty 로 계속해 주세요");
		}
	}

	/**
	 * 새 실행의 기록. 누적 patch 의 기준은 지금 작업 트리(--allow-dirty 의 기존 변경 포함)다. HEAD 를 기준으로 하면 첫
	 * stage 를 되돌릴 때 기존 변경까지 지워진다. 지난 실행의 기록은 버린다.
	 */
	private void start(ProjectState project) {
		String owner = this.session.store().project();
		if (!project.gitRoot()) {
			this.session.state(RunState.start(owner, "", "", project.bootVersion()));
			return;
		}
		String head = this.session.git().head();
		String tree = this.session.git().snapshotTree(Set.of(), this.session.ws().tempIndex());
		String start = (tree != null) ? this.session.git().pinStart(START_REF, tree) : null;
		if (head == null || start == null) {
			throw new GradleException("시작 시점의 작업 트리를 기록하지 못했어요 (git write-tree / commit-tree 실패)");
		}
		this.session.state(RunState.start(owner, head, start, project.bootVersion()));
	}

	/** stage 전에 의존성 버전, 원본 빌드, detect 결과를 모은다. 재개했다면 처음 실행 때 모은 것을 쓴다 */
	private void prepare(boolean resumed) {
		MigrationConfig config = this.session.config();
		RunnerConsole console = this.session.console();
		ProjectScanner scanner = this.session.components().scanner();
		MigrationWorkspace ws = this.session.ws();
		console.heading("[시작] 의존성 버전 / detect / 원본 빌드");
		if (!resumed
				&& scanner.resolvedVersions(this.session.gradle(), ws.start().versionsLog(), ws.start().versions())) {
			console.line("   의존성 {}개 → {}", TextFiles.countMatches(ws.start().versions(), "."), ws.start().versions());
		}
		if (config.gate().level().builds() && !config.preview() && !resumed) {
			BaselineBuild.Result baseline = new BaselineBuild(this.session.gradle(), this.session.verifyInitScript(),
					console, this.session.projectDir())
				.run(ws.start(), !config.gate().baselineTests());
			baseline.notes().forEach(this.session.history()::note);
			this.session.state(this.session.state().withBaseline(baseline.baseline()));
		}
		Path detectPatch = ws.start().detectPatch();
		if (resumed && Files.exists(detectPatch)) {
			console.line("   detect 결과는 처음 실행 때 것을 써요 → {}", detectPatch);
		}
		else if (scanner.detect(this.session.projectDir(), this.session.gradle(), ws.start())) {
			console.line("   detect {} 곳 → {}", TextFiles.countMatches(detectPatch, "~~>"), detectPatch);
		}
		else {
			// detect 는 수동 검토 대상 위치를 표시하는 용도라 실패해도 마이그레이션은 계속한다
			console.error("detect 실패 (검색 결과만 빠지고 계속 진행해요) → " + ws.start().detectLog());
			this.session.history().note("detect 가 실패해서 검색 결과가 없어요 (start/detect.log)");
		}
	}

	private void preview(MigrationPlan plan, int lastCompletedOrder) {
		PreviewRun preview = new PreviewRun(this.session.runner().paths(), this.session.runner().gradleFactory(),
				this.session.components().inspector(), this.session.console());
		if (this.session.isGit()) {
			preview.run(this.session.projectDir(), this.session.ws(), plan.stages(), lastCompletedOrder,
					this.session.projectRecipes(), this.session.gradle().javaHome());
			return;
		}
		Stage first = plan.stages().get(0);
		preview.firstStage(this.session.projectDir(), this.session.ws(), first, first.tag(lastCompletedOrder + 1),
				this.session.projectRecipes(), this.session.gradle());
	}

	private void finish(ProjectState project, MigrationPlan plan) {
		RunnerConsole console = this.session.console();
		MigrationWorkspace ws = this.session.ws();
		String startBoot = project.bootVersion();
		String finalBoot = this.session.components().inspector().bootVersion(this.session.projectDir());
		this.session.history().finished(startBoot, finalBoot);
		this.session.store().delete();
		if (project.gitRoot()) {
			this.session.git().deleteRef(START_REF);
		}
		console.heading("완료: Boot " + startBoot + " → " + finalBoot);
		console.line("   결과        : {}", ws.resultHtml().toUri());
		console.line("   기록        : {}", ws.history());
		console.line("   결과/patch  : {}", ws.dir());
		console.line("   다음 할 일  :");
		console.line("     1) 각 stage 결과의 체크리스트, 설정 키 변경, 제거 예정 API 확인");
		console.line("     2) 개발/스테이징 배포 후 기동 로그에서 \"The use of configuration keys that\" 검색 (외부 설정 저장소 확인)");
		console.line("     3) 정리가 끝나면 spring-boot-properties-migrator 의존성 제거");
		if (this.session.config().commit()) {
			console.line("   커밋       : {}", this.session.git().recentCommits(plan.stages().size()));
		}
	}

}
