package com.eottabom.migration.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.eottabom.migration.MigrationException;
import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.git.WorkingTree;
import com.eottabom.migration.io.TextFiles;
import com.eottabom.migration.pipeline.Resumption.Resumed;
import com.eottabom.migration.pipeline.step.BaselineBuild;
import com.eottabom.migration.plan.MigrationPlan;
import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.recipe.AssembledRecipe;
import com.eottabom.migration.version.ResolvedVersions;
import com.eottabom.migration.workspace.MigrationWorkspace;

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

	MigrationPipeline(RunnerComponents components, MigrationConfig config, MigrationWorkspace ws) {
		this.session = new RunSession(components, config, ws);
		this.stages = new StageRunner(this.session);
	}

	void run() {
		MigrationConfig config = this.session.config();
		Resumed resumed = config.preview() ? Resumed.NONE : new Resumption(this.session, this.stages).resume();

		ProjectState project = this.session.components()
			.withResolvedBootVersion(this.session.inspector().inspect(this.session.projectDir()), this.session.gradle(),
					this.session.ws().start());
		MigrationPlan plan = this.session.components().planner().plan(project, config);
		String summary = config.summary(plan.targetJava());
		announce(project, plan, summary);
		excludeRunnerOutputsFromGit();
		checkWorkingTree(resumed.active());
		if (plan.isEmpty()) {
			finishWithoutStages(project, plan, resumed);
			return;
		}
		if (!resumed.active() && !config.preview()) {
			start(project);
		}
		announceStages(project, plan);
		this.session.history().header(project, plan, summary, resumed.note());
		prepare(resumed.active());
		this.session.history().stageTable();

		// stage 번호는 지난 기록 뒤에 이어서 붙인다 (재개 시에는 다시 시도하는 stage 번호부터)
		int lastCompletedOrder = (resumed.lastCompletedOrder() != null) ? resumed.lastCompletedOrder()
				: this.session.ws().lastStageOrder();
		if (config.preview()) {
			preview(plan, lastCompletedOrder);
			return;
		}
		int order = lastCompletedOrder;
		for (Stage stage : plan.stages()) {
			order++;
			this.stages.run(stage, stage.tag(order));
		}
		finish(plan.stages().size() + (resumed.continued() ? 1 : 0));
	}

	private void announce(ProjectState project, MigrationPlan plan, String summary) {
		RunnerConsole console = this.session.console();
		console.heading("프로젝트 : " + this.session.projectDir());
		console.line("   현재     : Boot {} / Gradle {} / JAVA_HOME={}", project.bootVersion(),
				RunnerConsole.orUnknown(project.gradleVersion()),
				RunnerConsole.orDefault(this.session.gradle().javaHome()));
		console.line("   목표     : Boot {}  ({})", plan.targetBoot(), summary);
		console.projectRecipes(this.session.projectDir(), this.session.projectRecipes());
	}

	private void announceStages(ProjectState project, MigrationPlan plan) {
		RunnerConsole console = this.session.console();
		console.line("   Java     : {} -> {}", RunnerConsole.orUnknown(project.lowestDeclaredJava()),
				(plan.targetJava() == null) ? "유지" : plan.targetJava());
		console.line("   stage    : {}", plan.stageNames());
		console.targetLine(plan);
		console.notes(plan);
	}

	/** 결과 디렉토리와 조립한 레시피는 git 에 올리지 않는다 (patch 스냅샷과 커밋 대상에서 제외) */
	private void excludeRunnerOutputsFromGit() {
		if (this.session.isGit()) {
			this.session.git().exclude(MigrationWorkspace.DIR_NAME + "/");
			this.session.git().exclude(AssembledRecipe.RELATIVE_PATH);
		}
	}

	/** 남은 stage 가 없다. 멈췄던 마지막 stage 가 재개로 통과한 것이면 실행을 마무리한다 */
	private void finishWithoutStages(ProjectState project, MigrationPlan plan, Resumed resumed) {
		if (resumed.continued()) {
			finish(1);
			return;
		}
		this.session.console().heading("이미 Boot " + project.bootVersion() + " (목표 " + plan.targetBoot() + " 이상)예요.");
	}

	/** 새로 시작할 때는 자동 변경이 기존 변경과 섞이지 않도록 깨끗한 작업 트리를 요구한다 */
	private void checkWorkingTree(boolean resumed) {
		MigrationConfig config = this.session.config();
		WorkingTree workingTree = this.session.workingTree();
		if (config.commit() && !workingTree.repository()) {
			throw new MigrationException("--commit 은 git 저장소에서만 쓸 수 있어요");
		}
		if (!workingTree.dirty() || resumed || config.preview()) {
			return;
		}
		if (config.commit()) {
			throw new MigrationException("--commit 은 작업 트리가 깨끗해야 해요 (기존 변경이 커밋에 섞여요)");
		}
		if (!config.allowDirty()) {
			throw new MigrationException("작업 트리에 커밋되지 않은 변경이 있어요. 커밋하거나 --allow-dirty 로 계속해 주세요");
		}
	}

	/**
	 * 새 실행의 기록. 누적 patch 의 기준은 지금 작업 트리(--allow-dirty 의 기존 변경 포함)다. HEAD 를 기준으로 하면 첫
	 * stage 를 되돌릴 때 기존 변경까지 지워진다. 지난 실행의 기록은 버린다.
	 */
	private void start(ProjectState project) {
		if (!this.session.isGit()) {
			this.session.start("", "", project.bootVersion());
			return;
		}
		String head = this.session.git().head();
		String tree = this.session.git().snapshotTree(Set.of(), this.session.ws().tempIndex());
		String start = (tree != null) ? this.session.git().pinStart(START_REF, tree) : null;
		if (head == null || start == null) {
			throw new MigrationException("시작 시점의 작업 트리를 기록하지 못했어요 (git write-tree / commit-tree 실패)");
		}
		this.session.start(head, start, project.bootVersion());
	}

	/** stage 전에 의존성 버전, 원본 빌드, detect 결과를 모은다. 재개했다면 처음 실행 때 모은 것을 쓴다 */
	private void prepare(boolean resumed) {
		MigrationConfig config = this.session.config();
		RunnerConsole console = this.session.console();
		ProjectScanner scanner = this.session.components().scanner();
		MigrationWorkspace ws = this.session.ws();
		console.heading("[시작] 의존성 버전 / detect / 원본 빌드");
		if (!resumed) {
			resolveStartVersions(scanner, ws);
			this.session.startVersions(ResolvedVersions.read(ws.start().versions()));
		}
		if (config.gate().level().builds() && !config.preview() && !resumed) {
			BaselineBuild.Result baseline = new BaselineBuild(this.session.gradle(), console, this.session.projectDir())
				.run(ws.start(), !config.gate().baselineTests());
			baseline.notes().forEach(this.session.history()::note);
			this.session.recordBaseline(baseline.baseline());
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

	/** 실패해도 마이그레이션은 계속한다. 버전을 모르면 의존성 조건이 붙은 체크리스트와 첫 stage 의 의존성 변경이 빠진다 */
	private void resolveStartVersions(ProjectScanner scanner, MigrationWorkspace ws) {
		RunnerConsole console = this.session.console();
		if (scanner.resolvedVersions(this.session.gradle(), ws.start().versionsLog(), ws.start().versions())) {
			console.line("   의존성 {}개 → {}", TextFiles.countMatches(ws.start().versions(), "."), ws.start().versions());
			return;
		}
		console.error("의존성 버전 수집 실패 (의존성 조건이 붙은 체크리스트와 첫 stage 의 의존성 변경이 빠지고 계속 진행해요) → " + ws.start().versionsLog());
		this.session.history().note("시작할 때 의존성 버전을 모으지 못해 의존성 조건이 붙은 체크리스트가 빠져요 (start/versions.log)");
	}

	private void preview(MigrationPlan plan, int lastCompletedOrder) {
		PreviewRun preview = new PreviewRun(this.session.components().gradleFactory(), this.session.inspector(),
				this.session.console());
		if (this.session.isGit()) {
			preview.run(this.session.projectDir(), this.session.ws(), plan.stages(), lastCompletedOrder,
					this.session.projectRecipes(), this.session.gradle().javaHome());
			return;
		}
		Stage first = plan.stages().get(0);
		preview.firstStage(this.session.projectDir(), this.session.ws(), first, first.tag(lastCompletedOrder + 1),
				this.session.projectRecipes(), this.session.gradle());
	}

	/**
	 * @param completedStages 이번 실행에서 통과한 stage 수 (재개로 통과한 stage 포함)
	 */
	private void finish(int completedStages) {
		RunnerConsole console = this.session.console();
		MigrationWorkspace ws = this.session.ws();
		// 재개한 실행도 처음 시작할 때의 버전부터 센다
		String startBoot = this.session.state().startBoot();
		String finalBoot = this.session.currentBoot();
		this.session.history().finished(startBoot, finalBoot);
		this.session.deleteState();
		if (this.session.isGit()) {
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
			console.line("   커밋       : {}", this.session.git().recentCommits(completedStages));
		}
	}

}
