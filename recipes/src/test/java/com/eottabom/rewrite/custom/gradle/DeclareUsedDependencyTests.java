package com.eottabom.rewrite.custom.gradle;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.assertj.core.api.AbstractStringAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.gradle.toolingapi.Assertions.withToolingApi;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.srcTestJava;

class DeclareUsedDependencyTests implements RewriteTest {

	private static final String USES_LANG3 = """
			import org.apache.commons.lang3.StringUtils;
			class A { String s = StringUtils.trim(" a "); }
			""";

	@Override
	public void defaults(RecipeSpec spec) {
		// 테스트 JVM 이 JDK 25 라서 JDK 25 를 지원하는 Gradle 9.1 로 모델을 만든다
		spec.beforeRecipe(withToolingApi("9.1.0"))
			.recipe(new DeclareUsedDependency(List.of("org.apache.commons.lang3"), "org.apache.commons",
					"commons-lang3", "3.x", null));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("transitiveOnly")
	void declaresDependencyThatWasOnlyTransitive(String scenario, SourceSpecs buildScript, SourceSpecs source) {
		rewriteRun(mavenProject("app", buildScript, source));
	}

	// @formatter:off
	static Stream<Arguments> transitiveOnly() {
		// commons-text 가 commons-lang3 를 transitive 로 끌어온다
		return Stream.of(
			Arguments.of("main 에서 쓰면 implementation",
				buildGradle("""
					plugins { id 'java' }
					repositories { mavenCentral() }
					dependencies {
					    implementation 'org.apache.commons:commons-text:1.10.0'
					}
					""", (spec) -> spec.after(expect((script) -> script
						.containsPattern("implementation \"org.apache.commons:commons-lang3:3\\.\\d+(\\.\\d+)?\"")))),
				srcMainJava(java(USES_LANG3))),
			Arguments.of("테스트에서만 쓰면 testImplementation",
				buildGradle("""
					plugins { id 'java' }
					repositories { mavenCentral() }
					dependencies {
					    testImplementation 'org.apache.commons:commons-text:1.10.0'
					}
					""", (spec) -> spec.after(expect((script) -> script
						.contains("testImplementation \"org.apache.commons:commons-lang3:")))),
				srcTestJava(java(USES_LANG3))),
			Arguments.of("Kotlin DSL",
				buildGradleKts("""
					plugins { java }
					repositories { mavenCentral() }
					dependencies {
					    implementation("org.apache.commons:commons-text:1.10.0")
					}
					""", (spec) -> spec.after(expect((script) -> script
						.containsPattern("implementation\\(\"org.apache.commons:commons-lang3:3\\.\\d+(\\.\\d+)?\"\\)")))),
				srcMainJava(java(USES_LANG3))),
			Arguments.of("static import 로 쓰면",
				mainCommonsText(),
				srcMainJava(java("""
					import static org.apache.commons.lang3.StringUtils.trim;
					class A { String s = trim(" a "); }
					"""))),
			Arguments.of("import 없이 패키지 전체 이름으로 쓰면",
				mainCommonsText(),
				srcMainJava(java("""
					class A { String s = org.apache.commons.lang3.StringUtils.trim(" a "); }
					""")))
		);
	}

	private static SourceSpecs mainCommonsText() {
		return buildGradle("""
				plugins { id 'java' }
				repositories { mavenCentral() }
				dependencies {
				    implementation 'org.apache.commons:commons-text:1.10.0'
				}
				""", (spec) -> spec.after(expect((script) -> script
					.containsPattern("implementation \"org.apache.commons:commons-lang3:3\\.\\d+(\\.\\d+)?\""))));
	}
	// @formatter:on

	@Test
	void leavesDeclaredOrUnusedDependency() {
		rewriteRun(mavenProject("app", buildGradle("""
				plugins { id 'java' }
				repositories { mavenCentral() }
				dependencies {
				    implementation 'org.apache.commons:commons-lang3:3.14.0'
				}
				"""), srcMainJava(java(USES_LANG3))), mavenProject("other", buildGradle("""
				plugins { id 'java' }
				repositories { mavenCentral() }
				"""), srcMainJava(java("class B {}"))));
	}

	@Test
	void matchesPackageBeforeUpstreamRename() {
		rewriteRun(
				// 원본이 쓰는 commons-lang 2 는 테스트 classpath 에 없다 (import 텍스트로만 판단하는 것을 검증)
				(spec) -> spec.typeValidationOptions(TypeValidation.none())
					.recipes(
							new DeclareUsedDependency(List.of("org.apache.commons.lang3", "org.apache.commons.lang"),
									"org.apache.commons", "commons-lang3", null, null),
							new DeclareUsedDependency(List.of("org.apache.commons.io"), "commons-io", "commons-io",
									"2.x", null)),
				mavenProject("app",
						buildGradle("""
								plugins {
								    id 'java'
								    id 'org.springframework.boot' version '3.0.13'
								    id 'io.spring.dependency-management' version '1.1.7'
								}
								repositories { mavenCentral() }
								dependencies {
								    implementation 'org.springframework.boot:spring-boot-starter'
								}
								""",
								(spec) -> spec.after(expect((script) -> script
									.contains("implementation \"org.apache.commons:commons-lang3\"")
									.doesNotContain("commons-io")))),
						srcMainJava(java("""
								import org.apache.commons.lang.StringUtils;
								class A { String s = StringUtils.trim(" a "); }
								"""))));
	}

	@Test
	void declaresCommonsTextForWordUtilsMovedByCommonsLangMigration() {
		// MigrateCommonsLang2Usages 가 WordUtils 를 commons-text 로 옮기지만 upstream 의
		// AddDependency 는 precondition 에 걸려
		// 빌드 스크립트에서 돌지 않는다. 공통 보정이 원본의 사용처를 보고 선언한다
		rewriteRun(
				(spec) -> spec.typeValidationOptions(TypeValidation.none())
					.recipeFromResources("com.eottabom.rewrite.custom.gradle.DeclareUsedTransitiveDependencies"),
				mavenProject("app",
						buildGradle("""
								plugins {
								    id 'java'
								    id 'org.springframework.boot' version '3.0.13'
								    id 'io.spring.dependency-management' version '1.1.7'
								}
								repositories { mavenCentral() }
								dependencies {
								    implementation 'org.springframework.boot:spring-boot-starter'
								}
								""",
								(spec) -> spec.after(expect((script) -> script
									.containsPattern("implementation \"org.apache.commons:commons-text:1\\.\\d+")))),
						srcMainJava(java("""
								import org.apache.commons.lang.WordUtils;
								class A { String s = WordUtils.capitalize("a"); }
								"""))));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("commonsLang2Leftovers")
	void removesCommonsLang2DeclarationOnlyWhenNothingUsesIt(String scenario, String source, boolean removed) {
		String build = """
				plugins { id 'java' }
				repositories { mavenCentral() }
				dependencies {
				    implementation 'commons-lang:commons-lang:2.6'
				}
				""";
		rewriteRun(
				// 실제 실행에서는 대상 프로젝트의 commons-lang 2 jar 로 타입이 잡힌다. 테스트는 같은 타입을 스텁으로 둔다
				(spec) -> spec.recipeFromResources("com.eottabom.rewrite.custom.misc.RemoveUnusedCommonsLang2")
					.parser(JavaParser.fromJavaVersion()
						.dependsOn(
								"package org.apache.commons.lang; public class StringUtils { public static String trim(String s) { return s; } }",
								"package org.apache.commons.lang.time; public class DateUtils { public static final long MILLIS_PER_DAY = 1; }",
								"package org.apache.commons.lang3; public class StringUtils { public static String trim(String s) { return s; } }")),
				mavenProject("app",
						removed ? buildGradle(build,
								build.replace("    implementation 'commons-lang:commons-lang:2.6'\n", ""))
								: buildGradle(build),
						srcMainJava(java(source))));
	}

	static Stream<Arguments> commonsLang2Leftovers() {
		return Stream.of(Arguments.of("lang3 로 옮긴 뒤 lang2 를 쓰는 코드가 없으면 지운다", """
				import org.apache.commons.lang3.StringUtils;
				class A { String s = StringUtils.trim(" a "); }
				""", true), Arguments.of("lang2 를 쓰는 코드가 남아 있으면 둔다", """
				import org.apache.commons.lang.StringUtils;
				class A { String s = StringUtils.trim(" a "); }
				""", false), Arguments.of("lang2 하위 패키지를 쓰는 코드가 남아 있어도 둔다", """
				import org.apache.commons.lang.time.DateUtils;
				class A { long d = DateUtils.MILLIS_PER_DAY; }
				""", false));
	}

	@Test
	void usesSourceSetConfigurationForTestFixtures() {
		rewriteRun(
				(spec) -> spec.typeValidationOptions(TypeValidation.none())
					.recipe(new DeclareUsedDependency(List.of("org.apache.commons.io"), "commons-io", "commons-io",
							"2.x", null)),
				mavenProject("app",
						buildGradle("""
								plugins {
								    id 'java'
								    id 'java-test-fixtures'
								}
								repositories { mavenCentral() }
								""",
								(spec) -> spec.after(expect((script) -> script
									.contains("testFixturesImplementation \"commons-io:commons-io:2.")
									.doesNotContain("testImplementation \"commons-io")))),
						java("""
								import org.apache.commons.io.FileUtils;
								class Fixture { Object o = FileUtils.class; }
								""", (spec) -> spec.path("src/testFixtures/java/Fixture.java")
							.markers(JavaSourceSet.build("testFixtures", List.of())))));
	}

	@Test
	void addsOnlyToMainWhenTestAlsoUsesAndSkipsSourceSetWithoutConfiguration() {
		rewriteRun(
				(spec) -> spec.typeValidationOptions(TypeValidation.none())
					.recipe(new DeclareUsedDependency(List.of("org.apache.commons.lang3"), "org.apache.commons",
							"commons-lang3", "3.x", null)),
				mavenProject("app",
						buildGradle("""
								plugins { id 'java' }
								repositories { mavenCentral() }
								subprojects {
								    dependencies {
								    }
								}
								dependencies {
								    implementation 'org.apache.commons:commons-text:1.10.0'
								}
								""",
								(spec) -> spec.after(expect((script) -> script.containsOnlyOnce("commons-lang3")
									.contains("subprojects {\n    dependencies {\n    }\n}")
									.doesNotContain("testImplementation")))),
						srcMainJava(java("""
								class A {
								    String s = org.apache.commons.lang3.StringUtils.trim(System.out.toString());
								    int n = new int[0].length;
								}
								""")), srcTestJava(java(USES_LANG3)),
						java(USES_LANG3.replace("class A", "class Custom"),
								(spec) -> spec.path("src/custom/java/Custom.java")
									.markers(JavaSourceSet.build("custom", List.of())))));
	}

	@Test
	void ignoresSourcesAndBuildFilesOutsideJavaProject() {
		rewriteRun((spec) -> spec.typeValidationOptions(TypeValidation.none()),
				buildGradle("plugins { id 'java' }\n", (spec) -> spec.path("loose/build.gradle")),
				java(USES_LANG3.replace("class A", "class Loose"), (spec) -> spec.path("Loose.java")));
	}

	/** 결과 버전이 저장소 최신을 따라가서 문자열 대신 검증식으로 확인한다 */
	private static UnaryOperator<String> expect(Consumer<AbstractStringAssert<?>> check) {
		return (actual) -> {
			check.accept(assertThat(actual));
			return actual;
		};
	}

}
