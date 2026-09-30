package com.eottabom.migration.gradle;

import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/** 대상 프로젝트의 빌드 실행. 실제로는 {@link GradleWrapperProcess}, 러너 통합 테스트는 가짜 구현을 쓴다. */
public interface ProjectGradle {

	/** null 이면 현재 JAVA_HOME */
	@Nullable String javaHome();

	boolean run(Path log, List<String> args);

	boolean runQuietly(List<String> args);

	/** configFile 은 .rewrite/rewrite.assembled.yml, null 이면 플러그인 기본값 */
	boolean rewrite(Path log, String task, String recipe, Path rewriteInit, Path recipeLibs, @Nullable Path configFile);

	default boolean rewrite(Path log, String task, String recipe, Path rewriteInit, Path recipeLibs) {
		return rewrite(log, task, recipe, rewriteInit, recipeLibs, null);
	}

	@FunctionalInterface
	interface Factory {

		ProjectGradle create(Path projectDir, @Nullable String javaHome);

	}

}
