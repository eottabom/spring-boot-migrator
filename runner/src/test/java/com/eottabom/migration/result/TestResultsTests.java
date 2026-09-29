package com.eottabom.migration.result;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.eottabom.migration.GitFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TestResultsTests {

	@TempDir
	Path project;

	@Test
	void separatesSameTestNameInDifferentModules() {
		result("build/test-results/test/TEST-demo.UserTest.xml", "demo.UserTest", "works", true);
		result("api/build/test-results/test/TEST-demo.UserTest.xml", "demo.UserTest", "works", true);
		result("libs/web/build/test-results/test/TEST-demo.UserTest.xml", "demo.UserTest", "works", false);

		TestResults.Results results = TestResults.read(this.project, allResults());

		assertThat(results.failedTests()).containsExactly("api:demo.UserTest#works", "demo.UserTest#works");
		assertThat(results.total()).isEqualTo(3);
		assertThat(results.passedTests()).containsExactly("libs/web:demo.UserTest#works");
		assertThat(results.complete()).isTrue();
	}

	@Test
	void reportsResultThatCannotBeRead() {
		result("build/test-results/test/TEST-demo.OkTest.xml", "demo.OkTest", "works", true);
		Path half = this.project.resolve("build/test-results/test/TEST-demo.HalfTest.xml");
		GitFixture.write(half, "<testsuite name=\"demo.HalfTest\" tests=\"1\" failures=\"1\"><testcase");

		TestResults.Results results = TestResults.read(this.project, allResults());

		assertThat(results.failedTests()).containsExactly("demo.OkTest#works");
		assertThat(results.unreadable()).containsExactly(half);
		assertThat(results.complete()).isFalse();
	}

	@Test
	void snapshotKeepsOnlyResultsCreatedOrChangedAfterIt() {
		result("build/test-results/test/TEST-demo.OldTest.xml", "demo.OldTest", "old", true);
		Path rerun = result("build/test-results/test/TEST-demo.RerunTest.xml", "demo.RerunTest", "rerun", true);
		TestResults.Snapshot before = TestResults.Snapshot.take(this.project);
		result("reports/TEST-demo.CopyTest.xml", "demo.CopyTest", "copy", true);
		Path added = result("build/test-results/test/TEST-demo.NewTest.xml", "demo.NewTest", "new", true);
		result("build/test-results/test/TEST-demo.RerunTest.xml", "demo.RerunTest", "rerun", false);

		assertThat(before.changedFiles(List.of())).containsExactlyInAnyOrder(added, rerun);
	}

	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource({ "demo.UserTest#works, demo.UserTest", "api:demo.UserTest#works, demo.UserTest",
			"libs/web:demo.Outer$Inner#works, demo.Outer$Inner" })
	void extractsTestClassWithoutModule(String id, String expectedClass) {
		assertThat(TestResults.testClass(id)).isEqualTo(expectedClass);
	}

	private List<Path> allResults() {
		return new TestResults.Snapshot(this.project, Map.of()).changedFiles(List.of());
	}

	private Path result(String path, String className, String name, boolean failed) {
		Path file = this.project.resolve(path);
		GitFixture.write(file,
				"<testsuite name=\"" + className + "\" tests=\"1\" failures=\"" + (failed ? 1 : 0)
						+ "\" errors=\"0\"><testcase classname=\"" + className + "\" name=\"" + name + "\">"
						+ (failed ? "<failure message=\"x\">x</failure>" : "") + "</testcase></testsuite>");
		return file;
	}

}
