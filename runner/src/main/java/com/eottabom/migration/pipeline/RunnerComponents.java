package com.eottabom.migration.pipeline;

import java.nio.file.Path;

import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.pipeline.MigrationRunner.RunnerPaths;
import com.eottabom.migration.plan.MigrationPlanner;
import com.eottabom.migration.project.JdkLocator;
import com.eottabom.migration.project.ProjectInspector;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.workspace.RunFiles;
import org.gradle.api.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * 러너가 실행 내내 함께 쓰는 협력 객체.
 *
 * @param gradleFactory 대상 Gradle 을 띄우는 방법 (러너 통합 테스트는 가짜 구현으로 바꾼다)
 * @param schemaDir schema/ (재개 기록의 JSON Schema)
 */
record RunnerComponents(RunnerConsole console, ProjectInspector inspector, MigrationPlanner planner, Guides guides,
		ProjectScanner scanner, ProjectGradle.Factory gradleFactory, JdkSelection jdks, Path schemaDir) {

	static RunnerComponents assemble(RunnerPaths paths, Logger logger, ProjectGradle.Factory gradleFactory) {
		Guides guides = Guides.load(paths.guidesDir(), paths.schemaDir());
		RunnerConsole console = new RunnerConsole(logger);
		return new RunnerComponents(console, new ProjectInspector(), new MigrationPlanner(guides), guides,
				new ProjectScanner(), gradleFactory, new JdkSelection(new JdkLocator(), console), paths.schemaDir());
	}

	/** 프로젝트의 Java 버전에 맞는 JDK 로 대상 Gradle 을 띄운다 */
	ProjectGradle gradle(ProjectState project, MigrationConfig config) {
		return gradle(project.dir(), javaHome(project, config));
	}

	ProjectGradle gradle(Path projectDir, @Nullable String javaHome) {
		return this.gradleFactory.create(projectDir, javaHome);
	}

	@Nullable String javaHome(ProjectState project, MigrationConfig config) {
		return this.jdks.javaHome(project, config.build().usesCurrentJavaHome());
	}

	/** 빌드 파일에서 Boot 버전을 찾지 못하면 대상 Gradle 이 resolve 한 버전을 쓴다 */
	ProjectState withResolvedBootVersion(ProjectState project, ProjectGradle gradle, RunFiles files) {
		if (project.bootVersion() != null) {
			return project;
		}
		this.console.line("   빌드 파일에서 Boot 버전을 찾지 못해 대상 Gradle 이 resolve 한 버전을 읽을게요");
		String resolved = this.scanner.resolvedBootVersion(gradle, files);
		return (resolved != null) ? project.withBootVersion(resolved) : project;
	}

	/** custom 레시피를 끄면 upstream 결과만 비교하는 것이라 프로젝트 레시피도 붙이지 않는다 */
	static ProjectRecipes projectRecipes(MigrationConfig config) {
		return (!config.recipes().project() || !config.recipes().custom()) ? ProjectRecipes.none()
				: ProjectRecipes.discover(config.projectDir());
	}

}
