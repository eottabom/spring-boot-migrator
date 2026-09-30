package com.eottabom.migration.pipeline;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.git.Git;
import com.eottabom.migration.git.WorkingTree;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.pipeline.step.GateStep;
import com.eottabom.migration.pipeline.step.RecipeRun;
import com.eottabom.migration.pipeline.step.RewriteStep;
import com.eottabom.migration.project.ProjectInspector;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.version.ResolvedVersions;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.RunState;
import com.eottabom.migration.workspace.RunState.Baseline;
import com.eottabom.migration.workspace.RunState.Stopped;
import com.eottabom.migration.workspace.RunStateStore;
import org.jspecify.annotations.Nullable;

/**
 * 한 번의 migrationRun 이 stage 사이에 이어 쓰는 값. 재개 기록(run-state.json)은 바뀔 때마다 파일로 남긴다.
 */
final class RunSession {

	private final RunnerComponents components;

	private final MigrationConfig config;

	private final MigrationWorkspace ws;

	private final Git git;

	private final RunStateStore store;

	private final ProjectRecipes projectRecipes;

	private final RunHistory history;

	private final WorkingTree workingTree;

	/** stage 가 Java 버전을 올리면 다시 고른 JDK 로 바꾼다 */
	private ProjectGradle gradle;

	private RunState state = RunState.start("", "", "", null);

	/** 다음 stage 와 비교할 의존성 버전 (직전 stage 후, 처음에는 시작할 때 모은 것) */
	private ResolvedVersions previousVersions = ResolvedVersions.UNKNOWN;

	/** 마지막으로 통과한 stage 의 태그 */
	private @Nullable StageTag lastTag;

	RunSession(RunnerComponents components, MigrationConfig config, MigrationWorkspace ws) {
		this.components = components;
		this.config = config;
		this.ws = ws;
		this.git = new Git(config.projectDir());
		this.store = RunStateStore.of(ws, components.schemaDir());
		this.projectRecipes = RunnerComponents.projectRecipes(config);
		this.history = new RunHistory(ws, projectName());
		this.workingTree = RunnerOutputs.workingTree(config.projectDir());
		this.gradle = components.gradle(components.inspector().inspect(config.projectDir()), config);
	}

	MigrationConfig config() {
		return this.config;
	}

	MigrationWorkspace ws() {
		return this.ws;
	}

	RunnerComponents components() {
		return this.components;
	}

	RunnerConsole console() {
		return this.components.console();
	}

	ProjectInspector inspector() {
		return this.components.inspector();
	}

	Guides guides() {
		return this.components.guides();
	}

	Path projectDir() {
		return this.config.projectDir();
	}

	String projectName() {
		return String.valueOf(projectDir().getFileName());
	}

	/** 지금 빌드 파일에 적힌 Boot 버전 (레시피가 올린 뒤 다시 읽는다) */
	@Nullable String currentBoot() {
		return inspector().bootVersion(projectDir());
	}

	Git git() {
		return this.git;
	}

	ProjectRecipes projectRecipes() {
		return this.projectRecipes;
	}

	RunHistory history() {
		return this.history;
	}

	ProjectGradle gradle() {
		return this.gradle;
	}

	/** 실행을 시작할 때의 작업 트리 상태 */
	WorkingTree workingTree() {
		return this.workingTree;
	}

	boolean isGit() {
		return this.workingTree.repository();
	}

	RunState state() {
		return this.state;
	}

	/** 지난 실행이 남긴 재개 기록을 읽는다 (없으면 빈 값) */
	Optional<RunState> savedState() {
		return this.store.read();
	}

	/** 읽어 둔 기록이나 되돌릴 시점의 기록으로 바꾼다 */
	void restore(RunState state) {
		save(state);
	}

	/**
	 * 새 실행을 시작한다. 지난 실행의 기록은 버린다.
	 * @param baseRevision 실행을 시작한 HEAD (git 저장소가 아니면 빈 문자열)
	 * @param startRevision 누적 patch 의 기준 (git 저장소가 아니면 빈 문자열)
	 */
	void start(String baseRevision, String startRevision, @Nullable String startBoot) {
		save(RunState.start(this.store.project(), baseRevision, startRevision, startBoot));
	}

	void recordBaseline(Baseline baseline) {
		save(this.state.withBaseline(baseline));
	}

	/** 레시피가 만든 파일. patch 와 커밋에 넣는다 */
	void addCreatedFiles(Set<String> files) {
		save(this.state.withCreatedFiles(files));
	}

	/** 게이트에서 멈춘 자리를 남긴다. 같은 명령을 다시 실행하면 여기서 이어서 한다 */
	void stopAt(Stopped stopped) {
		save(this.state.stoppedAt(stopped, isGit() ? this.git.untracked() : Set.of()));
	}

	/** 멈췄던 stage 를 넘어섰다 (통과했거나 되돌렸다) */
	void clearStopped() {
		save(this.state.resumed());
	}

	/** 끝까지 마쳤다 */
	void deleteState() {
		this.store.delete();
	}

	/** 바뀐 기록을 파일로 남긴다 (preview 는 남기지 않는다) */
	private void save(RunState state) {
		this.state = state;
		if (!this.config.preview()) {
			this.store.write(state);
		}
	}

	ResolvedVersions previousVersions() {
		return this.previousVersions;
	}

	/** 첫 stage 는 시작할 때 모은 버전과 비교한다 */
	void startVersions(ResolvedVersions versions) {
		this.previousVersions = versions;
	}

	@Nullable StageTag lastTag() {
		return this.lastTag;
	}

	/** stage 를 통과했다. 다음 stage 는 이 stage 후의 버전과 비교한다 (모으지 못했으면 마지막으로 아는 버전) */
	void passed(StageTag tag, ResolvedVersions versionsAfter) {
		if (!versionsAfter.isUnknown()) {
			this.previousVersions = versionsAfter;
		}
		this.lastTag = tag;
	}

	/** 재개할 때 멈춘 stage 의 직전 stage 에서 이어 간다 */
	void continueAfter(@Nullable StageTag tag) {
		this.previousVersions = ResolvedVersions.read(this.ws.versionsAfter(tag));
		this.lastTag = tag;
	}

	/**
	 * 레시피가 Java 버전(toolchain, sourceCompatibility)을 올렸으면 대상 Gradle 을 띄울 JDK 를 다시 고른다. 시작할
	 * 때의 JDK 로 계속 돌면 Boot 3.0, java21 같은 stage 의 게이트가 낮은 JDK 에서 깨진다.
	 * @param project 레시피가 돈 뒤의 프로젝트 상태
	 */
	void refreshJdk(ProjectState project) {
		String javaHome = this.components.javaHome(project, this.config);
		if (!Objects.equals(javaHome, this.gradle.javaHome())) {
			console().line("   JAVA_HOME 변경: {} → {}", RunnerConsole.orDefault(this.gradle.javaHome()),
					RunnerConsole.orDefault(javaHome));
			this.gradle = this.components.gradle(projectDir(), javaHome);
		}
	}

	RecipeRun recipeRun() {
		return new RecipeRun(this.gradle, isGit() ? this.git : null);
	}

	RewriteStep rewriteStep() {
		return new RewriteStep(recipeRun(), console(), projectDir());
	}

	GateStep gateStep() {
		return new GateStep(this.gradle, console(), projectDir(), this.config.gate().testRetries(),
				this.state.baseline());
	}

}
