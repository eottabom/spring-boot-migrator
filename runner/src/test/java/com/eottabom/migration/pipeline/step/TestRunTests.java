package com.eottabom.migration.pipeline.step;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.gradle.VerifyInitScript;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class TestRunTests {

	@TempDir
	Path project;

	@Test
	void readsResultsFromReportDirectoryOutsideConvention() {
		Path custom = this.project.resolve("out/junit");
		TestRun.Result run = run((reportDirs) -> {
			write(custom.resolve("TEST-demo.AppTest.xml"), "<testsuite tests=\"1\"><testcase classname=\"demo.AppTest\""
					+ " name=\"t\"><failure/></testcase></testsuite>");
			write(reportDirs, custom + "\n");
		}, true);

		assertThat(run.tests().total()).isEqualTo(1);
		assertThat(run.tests().failedTests()).containsExactly("demo.AppTest#t");
		assertThat(run.tests().complete()).isTrue();
	}

	@Test
	void countsResultsOnceWhenProjectPathGoesThroughSymlink() throws IOException {
		// macOS 의 /tmp → /private/tmp 처럼 상위 디렉토리가 symlink 다
		Path real = Files.createDirectories(this.project.resolve("real/demo"));
		Path link = Files.createSymbolicLink(this.project.resolve("link"), real.getParent()).resolve("demo");
		Path xml = real.resolve("build/test-results/test/TEST-demo.AppTest.xml");
		TestRun.Result run = run(link, (reportDirs) -> {
			write(xml, "<testsuite tests=\"1\"><testcase classname=\"demo.AppTest\" name=\"t\"/></testsuite>");
			// Gradle 은 실제 경로를 알려 준다
			write(reportDirs, xml.getParent().toString() + "\n");
		}, true);

		assertThat(run.tests().total()).isEqualTo(1);
		assertThat(run.files()).containsExactly(link.resolve("build/test-results/test/TEST-demo.AppTest.xml"));
	}

	@Test
	void treatsTestTaskWithoutResultsAsUnreadable() {
		Path custom = this.project.resolve("out/junit");
		TestRun.Result run = run((reportDirs) -> write(reportDirs, custom + "\n"), true);

		assertThat(run.tests().total()).isZero();
		assertThat(run.tests().complete()).isFalse();
		assertThat(run.tests().unreadable()).containsExactly(custom);
	}

	@Test
	void filteredRetryMayLeaveModulesWithoutResults() {
		Path custom = this.project.resolve("out/junit");
		TestRun.Result run = run((reportDirs) -> write(reportDirs, custom + "\n"), false);

		assertThat(run.tests().complete()).isTrue();
	}

	private TestRun.Result run(Consumer<Path> build, boolean requireResults) {
		return run(this.project, build, requireResults);
	}

	private TestRun.Result run(Path projectDir, Consumer<Path> build, boolean requireResults) {
		Path reportDirs = this.project.resolve("dirs.txt");
		ProjectGradle gradle = new ProjectGradle() {
			@Override
			public @Nullable String javaHome() {
				return null;
			}

			@Override
			public boolean run(Path log, List<String> args) {
				assertThat(args).contains("-PmigrationTestResultsOut=" + reportDirs);
				build.accept(reportDirs);
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
		};
		return new TestRun(gradle, new VerifyInitScript(this.project.resolve("verify.gradle")), projectDir)
			.run(this.project.resolve("build.log"), reportDirs, List.of("build"), requireResults);
	}

	private static void write(Path file, String content) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
