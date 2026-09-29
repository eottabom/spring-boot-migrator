package com.eottabom.migration.gradle;

import java.nio.file.Path;
import java.util.Set;

import com.eottabom.migration.GitFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FailedTasksTests {

	@TempDir
	Path dir;

	@Test
	void readsFailedTasksFromInitScriptFileBeforeLog() {
		Path file = this.dir.resolve("failed-tasks.txt");
		Path log = this.dir.resolve("build.log");
		GitFixture.write(file, ":api:test\n\n:app:checkstyleMain\n");
		GitFixture.write(log, "Execution failed for task ':other:bootJar'.\n");

		assertThat(FailedTasks.read(file, log)).containsExactly(":api:test", ":app:checkstyleMain");
	}

	@Test
	void fallsBackToLogWhenInitScriptDidNotWriteFile() {
		Path log = this.dir.resolve("build.log");
		GitFixture.write(log, """
				* What went wrong:
				Execution failed for task ':app:bootJar'.
				Execution failed for task ':api:checkstyleMain'.
				""");

		assertThat(FailedTasks.read(this.dir.resolve("missing.txt"), log)).containsExactly(":api:checkstyleMain",
				":app:bootJar");
	}

	@Test
	void detectsCompileTaskFailures() {
		assertThat(FailedTasks.anyCompileTask(Set.of(":api:compileTestJava", ":app:test"))).isTrue();
		assertThat(FailedTasks.anyCompileTask(Set.of(":app:test", ":app:checkstyleMain"))).isFalse();
	}

}
