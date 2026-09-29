package com.eottabom.rewrite.custom.gradle;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.settingsGradle;
import static org.openrewrite.gradle.toolingapi.Assertions.withToolingApi;
import static org.openrewrite.toml.Assertions.toml;

/**
 * Gradle 모델이 있을 때 루트 프로젝트의 저장소에서 버전을 고르는지. 결과가 저장소 최신을 따라가서 패턴으로 확인한다.
 */
class UpgradeVersionCatalogResolutionTests implements RewriteTest {

	private static final String CATALOG = """
			[versions]
			boot = "3.3.5"

			[libraries]
			lang3 = { module = "org.apache.commons:commons-lang3", version = "3.12.0" }
			io = { module = "commons-io:commons-io", version = "2.99.0" }
			inject = { module = "javax.inject:javax.inject", version = "1" }
			text = { module = "org.apache.commons:commons-text", version = "1.10.0" }

			[plugins]
			boot = { id = "org.springframework.boot", version.ref = "boot" }
			""";

	@Override
	public void defaults(RecipeSpec spec) {
		spec.beforeRecipe(withToolingApi("9.1.0"));
	}

	@Test
	void selectsVersionsFromRootProjectRepositories() {
		rewriteRun(
				(spec) -> spec.recipe(new UpgradeVersionCatalog(List.of("plugin org.springframework.boot 3.4.x",
						"dependency org.apache.commons:commons-lang3 3.x", "dependency commons-io:commons-io 2.x",
						"change javax.inject:javax.inject jakarta.inject:jakarta.inject-api 2.x",
						"dependency org.apache.commons:commons-text 99.x"))),
				buildGradle("""
						plugins { id 'java' }
						repositories { mavenCentral() }
						dependencies { implementation 'org.apache.commons:commons-lang3:3.12.0' }
						"""), settingsGradle("rootProject.name = 'demo'"),
				toml(CATALOG, (spec) -> spec.path("gradle/libs.versions.toml").after((actual) -> {
					assertThat(actual).containsPattern("boot = \"3\\.4\\.\\d+\"")
						.containsPattern("commons-lang3\", version = \"3\\.\\d{2,}\\.\\d+\"")
						.contains("io = { module = \"commons-io:commons-io\", version = \"2.99.0\" }")
						.contains("inject = { module = \"jakarta.inject:jakarta.inject-api\", version = \"2.0.1\" }")
						.contains("text = { module = \"org.apache.commons:commons-text\", version = \"1.10.0\" }");
					return actual;
				})));
	}

	@Test
	void keepsVersionWhenRepositoryCannotBeReached() {
		rewriteRun((spec) -> spec.recipe(new UpgradeVersionCatalog(List.of("dependency org.apache.commons:* 3.x"))),
				buildGradle("""
						plugins { id 'java' }
						repositories { maven { url = 'http://localhost:1/unreachable' } }
						"""), toml(CATALOG, (spec) -> spec.path("gradle/libs.versions.toml")));
	}

}
