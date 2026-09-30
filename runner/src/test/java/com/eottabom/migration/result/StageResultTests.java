package com.eottabom.migration.result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.eottabom.migration.guide.ChecklistItem.Fix;
import com.eottabom.migration.guide.FailureHint;
import com.eottabom.migration.stage.StageId;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.version.ResolvedVersions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class StageResultTests {

	static final List<FailureHint> HINTS = List.of(new FailureHint(
			Pattern.compile("NoSuchBeanDefinitionException.*'taskExecutor'"), "applicationTaskExecutor 로 바꾼다"));

	@TempDir
	Path project;

	// @formatter:off
	static Stream<Arguments> failures() {
		return Stream.of(
			Arguments.of(
				"demo.app.OrderTest",
				"java.lang.AssertionError: expected 1\n\tat demo.app.OrderTest.saves(OrderTest.java:12)",
				"OrderTest", "saves", "AssertionError", "expected 1", "OrderTest.java:12", null
			),
			Arguments.of(
				"demo.app.OrderTest",
				"java.lang.ExceptionInInitializerError\n\tat java.base@21/jdk.internal.misc.Unsafe.ensureClassInitialized(Unsafe.java:1)\n"
						+ "Caused by: java.lang.IllegalStateException: no docker\n\tat app//demo.app.support.Containers.<clinit>(Containers.java:35)",
				"OrderTest", "saves", "IllegalStateException", "no docker", "Containers.java:35", null
			),
			Arguments.of(
				"demo.app.OrderTest$WhenPaid$Refund",
				"java.lang.IllegalStateException: outer\nCaused by: org.x.InnerException: root cause",
				"OrderTest", "WhenPaid > Refund > saves", "InnerException", "root cause", null, null
			),
			Arguments.of(
				"demo.app.AsyncTest",
				"org.springframework.beans.factory.NoSuchBeanDefinitionException: No bean named 'taskExecutor' available",
				"AsyncTest", "saves", "NoSuchBeanDefinitionException", "No bean named 'taskExecutor' available",
				null, "applicationTaskExecutor 로 바꾼다"
			)
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] {0} 실패 스택 분석 -> 기대 예외: {4}")
	@MethodSource("failures")
	void extractsInnermostCauseLocationAndHint(String className, String stackTrace, String expectedSimpleClassName,
			String expectedTestName, String expectedException, String expectedMessage, String expectedLocation,
			String expectedHint) {
		TestReport.TestFailure failure = TestReport.testFailure(className, "saves", "", stackTrace, HINTS, false);

		assertThat(failure.className()).endsWith(expectedSimpleClassName);
		assertThat(failure.testName()).isEqualTo(expectedTestName);
		assertThat(failure.exception()).isEqualTo(expectedException);
		assertThat(failure.message()).isEqualTo(expectedMessage);
		assertThat(failure.firstProjectFrame()).isEqualTo(expectedLocation);
		assertThat(failure.hint()).isEqualTo(expectedHint);
	}

	@ParameterizedTest(name = "[{index}] {0} -> {1} ({2})")
	@CsvSource({ "1.2.3, 2.0.0, MAJOR", "1.2.3, 1.3.0, MINOR", "1.2.3, 1.2.4, PATCH", "6.6.2.Final, 6.6.3.Final, PATCH",
			"33.4.8-jre, 33.5.0-jre, MINOR" })
	void classifiesVersionChange(String beforeVersion, String afterVersion, DependencyChanges.Level expectedLevel) {
		assertThat(DependencyChanges.level(beforeVersion, afterVersion)).isEqualTo(expectedLevel);
	}

	@ParameterizedTest(name = "[{index}] 레시피: {0} -> 커스텀 fix={1}")
	@CsvSource({ "com.eottabom.rewrite.custom.gradle.DeclareUsedDependency, true",
			"com.eottabom.rewrite.stage.Boot_3_4, false", "com.eottabom.rewrite.detect.ManualMigrationItems, false",
			"com.eottabom.rewrite.upstream.Boot_3_4, false", "com.eottabom.rewrite.custom.CommonFixes, false",
			"org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_4, false", "demo.migration.RenameGreeting, true" })
	void countsOnlyCustomAndProjectRecipesAsFixes(String recipeName, boolean expectedCustomFix) {
		assertThat(RecipeChanges.isCustomFix(recipeName, Set.of("demo.migration.RenameGreeting")))
			.isEqualTo(expectedCustomFix);
	}

	@Test
	void writesMarkdownAndJsonFromStageFiles() throws IOException {
		Path log = write("compile.log",
				this.project.resolve("src/A.java")
						+ ":3: warning: [removal] old() in A has been deprecated and marked for removal\n"
						+ this.project.resolve("src/A.java")
						+ ":3: warning: [removal] old() in A has been deprecated and marked for removal\n");
		write("build/test-results/test/TEST-demo.AppTest.xml",
				"""
						<testsuite name="demo.AppTest" tests="2" failures="1" errors="0">
						  <testcase classname="demo.AppTest" name="ok"/>
						  <testcase classname="demo.AppTest" name="boom"><failure message="x">java.lang.IllegalStateException: boom</failure></testcase>
						  <system-out><![CDATA[The use of configuration keys that have been renamed was found in the environment:

						Property source 'Config resource application.yml':
							Key: spring.redis.host
								Line: 3
								Replacement: spring.data.redis.host

						]]></system-out>
						</testsuite>
						""");
		Path find = write("scan.patch", "+++ b/src/A.java\n+    ObjectMapper m = /*~~>*/new ObjectMapper();\n");
		Path rewrite = write("rewrite.log", """
				Changes have been made to build.gradle by:
				    com.eottabom.rewrite.stage.Boot_3_4
				        com.eottabom.rewrite.custom.gradle.RemoveDependencyVersion: {groupId=org.apache.kafka}
				Please review and commit the results.
				""");
		Path before = write("before.txt", "org.hibernate.orm:hibernate-core=6.5.2.Final\norg.old:lib=1.0\n");
		Path after = write("after.txt", "org.hibernate.orm:hibernate-core=6.6.4.Final\norg.new:lib=1.0\n");
		List<ReportedChecklistItem> issues = List
			.of(new ReportedChecklistItem("x", Fix.MANUAL, "제목", "설명", null, null, "com.example.FindX", null));

		StageResult result = StageResult.assess(new StageResult.Input(StageId.boot("3.4"), List.of(StageId.boot("3.4")),
				this.project, new StageResult.Logs(log, rewrite, find), ResolvedVersions.read(before),
				ResolvedVersions.read(after),
				new StageResult.Gates(Outcome.PASSED, Outcome.PASSED, false, Set.of("demo.AppTest#flaky"), allResults(),
						List.of(this.project.resolve("bad.xml"))),
				new StageResult.Guidance(issues, "https://guide", HINTS), Set.of(), Set.of(),
				List.of("org.openrewrite.java.migrate.util.UseLocaleOf")));
		result.write(this.project.resolve("out.md"), this.project.resolve("out.json"));
		StageSummary summary = result.summary();

		String md = Files.readString(this.project.resolve("out.md"));
		assertThat(md).contains("# Spring Boot 3.4 마이그레이션 결과", "❌ 1 / 2 실패", "IllegalStateException",
				"`spring.redis.host` → `spring.data.redis.host`",
				"old() in A has been deprecated and marked for removal** 1곳 (src/A.java:3)",
				"com.eottabom.rewrite.custom.gradle.RemoveDependencyVersion",
				"| `org.hibernate.orm:hibernate-core` | 6.5.2.Final | 6.6.4.Final | minor |", "추가된 의존성 `org.new:lib`",
				"**제목**", "`src/A.java` 의 `ObjectMapper m = new ObjectMapper();`");
		Map<String, Object> json = new Yaml().load(Files.readString(this.project.resolve("out.json")));
		Map<String, Object> tests = (Map<String, Object>) Objects.requireNonNull(json.get("tests"));
		assertThat(tests).containsEntry("total", 2);
		assertThat((List<Map<String, Object>>) tests.get("failures")).singleElement()
			.satisfies((failure) -> assertThat(failure).containsEntry("className", "demo.AppTest")
				.containsEntry("testName", "boom")
				.containsEntry("existing", false));
		Map<String, Object> deps = (Map<String, Object>) Objects.requireNonNull(json.get("deps"));
		assertThat((List<Map<String, Object>>) deps.get("changed")).singleElement()
			.satisfies((change) -> assertThat(change).containsEntry("level", "minor"));
		Map<String, Object> changes = (Map<String, Object>) Objects.requireNonNull(json.get("changes"));
		assertThat((List<Object>) changes.get("files")).containsExactly("build.gradle");
		assertThat((List<Object>) changes.get("upstream")).isEmpty();
		assertThat((List<Object>) tests.get("flaky")).containsExactly("demo.AppTest#flaky");
		assertThat((List<Object>) tests.get("unreadable")).containsExactly("bad.xml");
		assertThat((List<Object>) json.get("deprecationFixes"))
			.containsExactly("org.openrewrite.java.migrate.util.UseLocaleOf");
		assertThat((List<Object>) json.get("covers")).containsExactly("3.4");
		assertThat(json).containsEntry("compile", "ok").containsEntry("source", "https://guide");
		assertThat(summary.historyRow(StageTag.parse("01-boot-3.4"))).isEqualTo(
				"| 3.4 | ✅ 통과 | ❌ 1 / 2 실패 (다시 돌려 통과한 1개 포함) | ✅ 통과 | 1 개 (custom 레시피 1 종) | 1 곳 | 사람 1 / 확인 0 / 자동 0 | [01-boot-3.4](01-boot-3.4/result.md) |\n");
		assertThat(md).contains("## deprecated API 대체", "`org.openrewrite.java.migrate.util.UseLocaleOf`", "## 레시피 변경");
		assertThat(md).contains(String.join("\n", summary.table()));
		assertMatchesSchema(this.project.resolve("out.json"));
	}

	/** result.html 과의 계약 (schema/result.schema.json) */
	private static void assertMatchesSchema(Path json) throws IOException {
		JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
			.getSchema(SchemaLocation.of(Path.of("../schema/result.schema.json").toUri().toString()));
		Set<ValidationMessage> errors = schema.validate(new ObjectMapper().readTree(json.toFile()));
		assertThat(errors).isEmpty();
	}

	@Test
	void separatesTestsThatAlreadyFailedBeforeMigration() throws IOException {
		write("build/test-results/test/TEST-demo.AppTest.xml",
				"""
						<testsuite name="demo.AppTest" tests="2" failures="1" errors="0">
						  <testcase classname="demo.AppTest" name="ok"/>
						  <testcase classname="demo.AppTest" name="boom"><failure message="x">java.lang.IllegalStateException: boom</failure></testcase>
						</testsuite>
						""");
		Path none = this.project.resolve("missing");

		StageResult
			.assess(new StageResult.Input(StageId.boot("4.1"), List.of(StageId.boot("4.1")), this.project,
					new StageResult.Logs(none, none, none), ResolvedVersions.read(none), ResolvedVersions.UNKNOWN,
					new StageResult.Gates(Outcome.PASSED, Outcome.PASSED, false, Set.of(), allResults(), List.of()),
					new StageResult.Guidance(List.of(), null, HINTS), Set.of(), Set.of("demo.AppTest#boom"), List.of()))
			.write(this.project.resolve("out.md"), this.project.resolve("out.json"));

		assertMatchesSchema(this.project.resolve("out.json"));
		assertThat(Files.readString(this.project.resolve("out.md")))
			.contains("✅ 1개 통과 (원본에서도 실패하던 1개 제외)", "## 원본에서도 실패하던 테스트 (1건)", "- `demo.AppTest` boom")
			.doesNotContain("## 실패한 테스트");
	}

	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|', value = { "src/A.java|`src/A.java`", "a`b|``a`b``", "`tick|`` `tick ``" })
	void wrapsInlineCodeSafely(String text, String expected) {
		assertThat(ResultMarkdown.code(text)).isEqualTo(expected);
	}

	private Path write(String path, String content) throws IOException {
		Path file = this.project.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return file;
	}

	private List<Path> allResults() {
		return new TestResults.Snapshot(this.project, Map.of()).changedFiles(List.of());
	}

}
