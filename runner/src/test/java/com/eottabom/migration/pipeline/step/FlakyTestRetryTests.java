package com.eottabom.migration.pipeline.step;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.gradle.VerifyInitScript;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.workspace.MigrationWorkspace;
import org.gradle.api.logging.Logging;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FlakyTestRetryTests {

	@TempDir
	Path project;

	@Test
	void buildsTestFilterFromTopLevelClassOfEachFailedTest() {
		assertThat(FlakyTestRetry.testClasses(Set.of("demo.AppTest#a", "api:demo.Outer$Inner#b", "demo.AppTest#c")))
			.containsExactly("demo.AppTest*", "demo.Outer*");
	}

	@ParameterizedTest
	@ValueSource(strings = { "deleted", "unreadable", "skipped", "missing", "unchanged", "command-failed" })
	void keepsFailuresWithoutEvidenceOfSuccessfulRetry(String scenario) throws IOException {
		assertRetry(scenario, true);
	}

	@Test
	void clearsOnlyExplicitlyPassedTests() throws IOException {
		assertRetry("passed", false);
	}

	private void assertRetry(String scenario, boolean expectFailure) throws IOException {
		MigrationWorkspace ws = MigrationWorkspace.in(this.project);
		Path xml = this.project.resolve("build/test-results/test/TEST-demo.AppTest.xml");
		Files.createDirectories(xml.getParent());
		Files.writeString(xml, result("failure", "target"));
		ProjectGradle build = new ProjectGradle() {
			@Override
			public @Nullable String javaHome() {
				return null;
			}

			@Override
			public boolean run(Path log, List<String> args) {
				try {
					switch (scenario) {
						case "deleted" -> Files.delete(xml);
						case "unreadable" -> Files.writeString(xml, "<testsuite");
						case "skipped" -> Files.writeString(xml, result("skipped", "target"));
						case "missing" -> Files.writeString(xml, result("", "other"));
						case "unchanged" -> {
						}
						default -> Files.writeString(xml, result("", "target"));
					}
					return !scenario.equals("command-failed");
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}

			@Override
			public boolean runQuietly(List<String> args) {
				throw new AssertionError();
			}

			@Override
			public boolean rewrite(Path log, String task, String recipe, Path init, Path libs, @Nullable Path config) {
				throw new AssertionError();
			}
		};
		FlakyTestRetry.Retried retried = retry(build, 1).retry(ws.stage(StageTag.parse("01-boot-3.4")),
				Set.of("demo.AppTest#target"), List.of(xml));
		assertThat(retried.failing()).isEqualTo(expectFailure ? Set.of("demo.AppTest#target") : Set.of());
		assertThat(retried.flaky()).isEqualTo(expectFailure ? Set.of() : Set.of("demo.AppTest#target"));
	}

	@Test
	void restoresResultsTheRetryDeletedAndKeepsLastResultOfEachRetriedClass() throws IOException {
		MigrationWorkspace ws = MigrationWorkspace.in(this.project);
		Path dir = this.project.resolve("build/test-results/test");
		Path app = dir.resolve("TEST-demo.AppTest.xml");
		Path other = dir.resolve("TEST-demo.OtherTest.xml");
		Path slow = dir.resolve("TEST-demo.SlowTest.xml");
		Files.createDirectories(dir);
		Files.writeString(app, result("failure", "a", "demo.AppTest"));
		Files.writeString(other, result("", "o", "demo.OtherTest"));
		Files.writeString(slow, result("failure", "s", "demo.SlowTest"));
		List<Path> build = List.of(app, other, slow);
		// 재시도마다 test 태스크가 결과 디렉토리를 비우고 거른 클래스만 쓴다. 1회차에 AppTest 가 통과하고 2회차는 SlowTest 만 돈다
		int[] attempt = { 0 };
		ProjectGradle gradle = new RetryOnly((args) -> {
			attempt[0]++;
			clear(dir);
			if (args.contains("demo.AppTest*")) {
				write(app, result("", "a", "demo.AppTest"));
			}
			write(slow, result("failure", "s", "demo.SlowTest"));
		});

		Set<String> remaining = retry(gradle, 2)
			.retry(ws.stage(StageTag.parse("01-boot-3.4")), Set.of("demo.AppTest#a", "demo.SlowTest#s"), build)
			.failing();

		assertThat(attempt[0]).isEqualTo(2);
		assertThat(remaining).containsExactly("demo.SlowTest#s");
		assertThat(Files.readString(other)).isEqualTo(result("", "o", "demo.OtherTest"));
		// 2회차에 지워진 AppTest 는 build 결과(실패)가 아니라 1회차 결과(통과)로 되돌린다
		assertThat(Files.readString(app)).isEqualTo(result("", "a", "demo.AppTest"));
		assertThat(ws.stage(StageTag.parse("01-boot-3.4")).keptResults()).doesNotExist();
	}

	@Test
	void doesNotCopyResultsWhenNothingIsRetried() {
		MigrationWorkspace ws = MigrationWorkspace.in(this.project);
		ProjectGradle gradle = new RetryOnly((args) -> {
			throw new AssertionError("다시 돌리지 않는다");
		});

		retry(gradle, 1).retry(ws.stage(StageTag.parse("01-boot-3.4")), Set.of(),
				List.of(this.project.resolve("build/test-results/test/TEST-x.xml")));

		assertThat(ws.stage(StageTag.parse("01-boot-3.4")).keptResults()).doesNotExist();
	}

	private FlakyTestRetry retry(ProjectGradle gradle, int retries) {
		return new FlakyTestRetry(gradle, new VerifyInitScript(this.project.resolve("verify.gradle")),
				new RunnerConsole(Logging.getLogger(FlakyTestRetryTests.class)), this.project, retries);
	}

	private static void clear(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.toList()) {
				Files.delete(file);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void write(Path file, String content) {
		try {
			Files.writeString(file, content);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String result(String status, String name, String classname) {
		return "<testsuite tests=\"1\"><testcase classname=\"" + classname + "\" name=\"" + name + "\">"
				+ (status.isEmpty() ? "" : "<" + status + "/>") + "</testcase></testsuite>";
	}

	private static String result(String status, String name) {
		return result(status, name, "demo.AppTest");
	}

	/** test --tests 만 받는 가짜 대상 빌드 */
	private record RetryOnly(Consumer<List<String>> onRetry) implements ProjectGradle {

		@Override
		public @Nullable String javaHome() {
			return null;
		}

		@Override
		public boolean run(Path log, List<String> args) {
			this.onRetry.accept(args);
			return true;
		}

		@Override
		public boolean runQuietly(List<String> args) {
			throw new AssertionError();
		}

		@Override
		public boolean rewrite(Path log, String task, String recipe, Path init, Path libs, @Nullable Path config) {
			throw new AssertionError();
		}

	}

}
