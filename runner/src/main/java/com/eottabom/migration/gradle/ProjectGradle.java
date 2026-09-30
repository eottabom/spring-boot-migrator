package com.eottabom.migration.gradle;

import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/** 대상 프로젝트의 빌드 실행. 실제로는 {@link GradleWrapperProcess}, 러너 통합 테스트는 가짜 구현을 쓴다. */
public interface ProjectGradle {

	/** null 이면 현재 JAVA_HOME */
	@Nullable String javaHome();

	/** verify.init.gradle 을 붙여 실행하고 출력을 log 에 남긴다 */
	boolean verify(Path log, List<String> args);

	/** verify.init.gradle 을 붙여 실행하되 결과만 보고 로그는 남기지 않는다 */
	boolean verifyQuietly(List<String> args);

	/**
	 * rewrite.init.gradle 을 붙여 레시피를 돌린다.
	 * @param configFile .rewrite/rewrite.assembled.yml, null 이면 플러그인 기본값
	 */
	boolean rewrite(Path log, RewriteTask task, String recipe, @Nullable Path configFile);

	default boolean rewrite(Path log, RewriteTask task, String recipe) {
		return rewrite(log, task, recipe, null);
	}

	enum RewriteTask {

		/** 소스를 바꾼다 */
		RUN("rewriteRun"),

		/** 소스는 그대로 두고 build/reports/rewrite/rewrite.patch 만 만든다 */
		DRY_RUN("rewriteDryRun");

		private final String taskName;

		RewriteTask(String taskName) {
			this.taskName = taskName;
		}

		public String taskName() {
			return this.taskName;
		}

	}

	@FunctionalInterface
	interface Factory {

		ProjectGradle create(Path projectDir, @Nullable String javaHome);

	}

}
