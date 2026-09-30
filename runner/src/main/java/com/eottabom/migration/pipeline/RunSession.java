package com.eottabom.migration.pipeline;

import java.nio.file.Path;
import java.util.Objects;

import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.gradle.VerifyInitScript;
import com.eottabom.migration.pipeline.step.GateStep;
import com.eottabom.migration.pipeline.step.RewriteStep;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.workspace.Git;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.RunState;
import com.eottabom.migration.workspace.RunStateStore;
import org.jspecify.annotations.Nullable;

/**
 * 한 번의 migrationRun 이 stage 사이에 이어 쓰는 값. 재개 기록(run-state.json)은 git 저장소일 때만 파일로 남긴다.
 */
final class RunSession {

	private final MigrationRunner runner;

	private final MigrationConfig config;

	private final MigrationWorkspace ws;

	private final Git git;

	private final RunStateStore store;

	private final ProjectRecipes projectRecipes;

	private final RunHistory history;

	/** stage 가 Java 버전을 올리면 다시 고른 JDK 로 바꾼다 */
	private ProjectGradle gradle;

	private boolean isGit;

	private RunState state = RunState.start("", "", "", null);

	/** 다음 stage 와 비교할 의존성 버전 (직전 stage 후, 처음에는 시작할 때 모은 것) */
	private Path previousVersions;

	/** 마지막으로 통과한 stage 의 태그 */
	private @Nullable String lastTag;

	RunSession(MigrationRunner runner, MigrationConfig config, MigrationWorkspace ws) {
		this.runner = runner;
		this.config = config;
		this.ws = ws;
		this.git = new Git(config.projectDir());
		this.store = RunStateStore.of(ws, runner.paths().schemaDir());
		this.projectRecipes = MigrationRunner.projectRecipes(config);
		this.history = new RunHistory(ws, projectName());
		this.gradle = runner.gradle(components().inspector().inspect(config.projectDir()), config);
		this.previousVersions = ws.start().versions();
	}

	MigrationConfig config() {
		return this.config;
	}

	MigrationWorkspace ws() {
		return this.ws;
	}

	RunnerComponents components() {
		return this.runner.components();
	}

	MigrationRunner runner() {
		return this.runner;
	}

	RunnerConsole console() {
		return components().console();
	}

	Path projectDir() {
		return this.config.projectDir();
	}

	String projectName() {
		return String.valueOf(projectDir().getFileName());
	}

	Git git() {
		return this.git;
	}

	RunStateStore store() {
		return this.store;
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

	boolean isGit() {
		return this.isGit;
	}

	void isGit(boolean isGit) {
		this.isGit = isGit;
	}

	RunState state() {
		return this.state;
	}

	/** 바뀐 기록을 git 저장소면 파일로 남긴다 (preview 는 남기지 않는다) */
	void state(RunState state) {
		this.state = state;
		if (this.isGit && !this.config.preview()) {
			this.store.write(state);
		}
	}

	Path previousVersions() {
		return this.previousVersions;
	}

	@Nullable String lastTag() {
		return this.lastTag;
	}

	/** stage 를 통과했다. 다음 stage 는 이 stage 후의 버전과 비교한다 */
	void passed(String tag) {
		this.previousVersions = this.ws.versionsAfter(tag);
		this.lastTag = tag;
	}

	/** 재개할 때 멈춘 stage 의 직전 stage 에서 이어 간다 */
	void continueAfter(@Nullable String tag) {
		this.previousVersions = this.ws.versionsAfter(tag);
		this.lastTag = tag;
	}

	/**
	 * 레시피가 Java 버전(toolchain, sourceCompatibility)을 올렸으면 대상 Gradle 을 띄울 JDK 를 다시 고른다. 시작할
	 * 때의 JDK 로 계속 돌면 Boot 3.0, java21 같은 stage 의 게이트가 낮은 JDK 에서 깨진다.
	 */
	void refreshJdk() {
		ProjectState now = components().inspector().inspect(projectDir());
		String javaHome = this.runner.jdks().javaHome(now, this.config.build().currentJavaHome());
		if (!Objects.equals(javaHome, this.gradle.javaHome())) {
			console().line("   JAVA_HOME 변경: {} → {}", RunnerConsole.orDefault(this.gradle.javaHome()),
					RunnerConsole.orDefault(javaHome));
			this.gradle = this.runner.gradleFactory().create(projectDir(), javaHome);
		}
	}

	VerifyInitScript verifyInitScript() {
		return components().scanner().verifyInitScript();
	}

	RewriteStep rewriteStep() {
		return new RewriteStep(this.gradle, console(), projectDir(), this.runner.paths().rewriteInit(),
				this.runner.paths().recipeLibs(), this.isGit ? this.git : null);
	}

	GateStep gateStep() {
		return new GateStep(this.gradle, verifyInitScript(), console(), projectDir(), this.config.gate().testRetries(),
				this.state.baseline());
	}

}
