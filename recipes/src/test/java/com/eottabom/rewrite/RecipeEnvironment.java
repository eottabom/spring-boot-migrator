package com.eottabom.rewrite;

import org.jspecify.annotations.Nullable;
import org.openrewrite.config.Environment;

/**
 * 테스트 classpath 의 레시피 전체. 읽는 데 메모리가 많이 들어 테스트와 생성기가 한 번 만든 것을 같이 쓴다.
 */
final class RecipeEnvironment {

	private static @Nullable Environment environment;

	private RecipeEnvironment() {
	}

	static synchronized Environment get() {
		if (environment == null) {
			environment = Environment.builder().scanRuntimeClasspath().build();
		}
		return environment;
	}

}
