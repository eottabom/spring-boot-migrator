package com.eottabom.migration.pipeline;

import java.nio.file.Path;

import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.config.MigrationConfig.BuildSettings;
import com.eottabom.migration.gradle.GradleWrapperProcess;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.plan.MigrationPlan;
import com.eottabom.migration.project.JdkLocator;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.RunFiles;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

/**
 * 태스크 진입점. migrationScan, migrationPlan, migrationVerify 는 여기서 끝나고 migrationRun 은
 * {@link MigrationPipeline} 이 돈다.
 */
public final class MigrationRunner {

	private final RunnerPaths paths;

	private final ProjectGradle.Factory gradleFactory;

	private final RunnerComponents components;

	private final JdkSelection jdks;

	/**
	 * @param build 대상 Gradle 을 띄우는 설정 (JVM 옵션, 제한 시간)
	 */
	public MigrationRunner(RunnerPaths paths, BuildSettings build, Logger logger) {
		this(paths, logger,
				(dir, javaHome) -> new GradleWrapperProcess(dir, javaHome, build.jvmArgs(), build.timeout(), logger));
	}

	/** 대상 빌드 실행을 바꿔 끼운다 (러너 통합 테스트) */
	MigrationRunner(RunnerPaths paths, Logger logger, ProjectGradle.Factory gradleFactory) {
		this.paths = paths;
		this.gradleFactory = gradleFactory;
		this.components = RunnerComponents.assemble(paths, logger);
		this.jdks = new JdkSelection(new JdkLocator(), this.components.console());
	}

	/** 현재 상태, resolve 된 의존성, detect 레시피가 찾은 위치. 소스는 바꾸지 않는다. */
	public void scan(MigrationConfig config) {
		ProjectState inspected = this.components.inspector().inspect(config.projectDir());
		ProjectGradle gradle = gradle(inspected, config);
		ProjectState project = withResolvedBootVersion(inspected, gradle,
				MigrationWorkspace.in(config.projectDir()).scan());
		new ScanCommand(this.components.scanner(), this.components.console()).scan(project, gradle);
	}

	/** 실행할 stage 만 보여준다. 대상 프로젝트의 Gradle 을 띄우지 않는다. */
	public MigrationPlan plan(MigrationConfig config) {
		ProjectState project = this.components.inspector().inspect(config.projectDir());
		MigrationPlan plan = planOrFail(project, config);
		new PlanPrinter(this.components.console(), this.components.guides()).print(project, plan,
				projectRecipes(config));
		return plan;
	}

	/** 현재 소스의 컴파일(+제거 예정 API 경고)과 build(전체 테스트 + 패키징). 소스는 바꾸지 않는다. */
	public void verify(MigrationConfig config) {
		ProjectState project = this.components.inspector().inspect(config.projectDir());
		new VerifyCommand(this.components.scanner(), this.components.console()).verify(project, gradle(project, config),
				config.gate().level());
	}

	public void run(MigrationConfig config) {
		MigrationWorkspace ws = MigrationWorkspace.in(config.projectDir());
		RunLock lock = RunLock.acquire(ws);
		try {
			new MigrationPipeline(this, config, ws).run();
		}
		finally {
			lock.close();
		}
	}

	RunnerComponents components() {
		return this.components;
	}

	RunnerPaths paths() {
		return this.paths;
	}

	ProjectGradle.Factory gradleFactory() {
		return this.gradleFactory;
	}

	JdkSelection jdks() {
		return this.jdks;
	}

	ProjectGradle gradle(ProjectState project, MigrationConfig config) {
		return this.gradleFactory.create(project.dir(), this.jdks.javaHome(project, config.build().currentJavaHome()));
	}

	/** 빌드 파일에서 Boot 버전을 찾지 못하면 대상 Gradle 이 resolve 한 버전을 쓴다 */
	ProjectState withResolvedBootVersion(ProjectState project, ProjectGradle gradle, RunFiles files) {
		if (project.bootVersion() != null) {
			return project;
		}
		this.components.console().line("   빌드 파일에서 Boot 버전을 찾지 못해 대상 Gradle 이 resolve 한 버전을 읽을게요");
		String resolved = this.components.scanner().resolvedBootVersion(gradle, files);
		return (resolved != null) ? project.withBootVersion(resolved) : project;
	}

	MigrationPlan planOrFail(ProjectState project, MigrationConfig config) {
		try {
			return this.components.planner().plan(project, config);
		}
		catch (IllegalArgumentException ex) {
			throw new GradleException(String.valueOf(ex.getMessage()));
		}
	}

	/** custom 레시피를 끄면 upstream 결과만 비교하는 것이라 프로젝트 레시피도 붙이지 않는다 */
	static ProjectRecipes projectRecipes(MigrationConfig config) {
		return (!config.recipes().project() || !config.recipes().custom()) ? ProjectRecipes.none()
				: ProjectRecipes.discover(config.projectDir());
	}

	/**
	 * @param rewriteInit init/rewrite.init.gradle
	 * @param verifyInit init/verify.init.gradle
	 * @param recipeLibs recipes/build/recipe-libs (레시피 jar 와 의존 jar)
	 * @param guidesDir guides/ (버전별 가이드)
	 * @param schemaDir schema/ (가이드, 설정 파일, 재개 기록의 JSON Schema)
	 */
	public record RunnerPaths(Path rewriteInit, Path verifyInit, Path recipeLibs, Path guidesDir, Path schemaDir) {
	}

}
