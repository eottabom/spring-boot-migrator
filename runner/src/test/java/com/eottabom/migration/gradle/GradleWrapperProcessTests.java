package com.eottabom.migration.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gradlew 대신 셸 스크립트를 두고 실행 결과와 제한 시간을 확인한다.
 */
@DisabledOnOs(OS.WINDOWS)
class GradleWrapperProcessTests {

	private static final InitScripts SCRIPTS = new InitScripts(Path.of("rewrite.init.gradle"),
			Path.of("verify.init.gradle"), Path.of("recipe-libs"));

	@TempDir
	Path project;

	@Test
	void stopsBuildThatRunsPastTimeout() throws IOException {
		gradlew("sleep 30");
		GradleWrapperProcess gradle = new GradleWrapperProcess(this.project, null, "-Xmx64m", Duration.ofSeconds(1),
				SCRIPTS, Logging.getLogger(GradleWrapperProcessTests.class));
		long start = System.currentTimeMillis();

		boolean passed = gradle.run(this.project.resolve("build.log"), List.of("build"));

		assertThat(passed).isFalse();
		assertThat(System.currentTimeMillis() - start).isLessThan(20_000);
		assertThat(Files.readString(this.project.resolve("build.log"))).contains("시간 초과");
	}

	@Test
	void reportsExitCodeAndKeepsOutputInLog() throws IOException {
		gradlew("echo \"> Task :compileJava\"; echo \"args $*\"; exit 3");
		GradleWrapperProcess gradle = new GradleWrapperProcess(this.project, null, "-Xmx64m", Duration.ofMinutes(1),
				SCRIPTS, Logging.getLogger(GradleWrapperProcessTests.class));

		boolean passed = gradle.run(this.project.resolve("build.log"), List.of("build", "--continue"));

		assertThat(passed).isFalse();
		assertThat(Files.readString(this.project.resolve("build.log"))).contains("> Task :compileJava",
				"-Dorg.gradle.daemon.idletimeout=600000", "--no-configuration-cache build --continue");
		assertThat(gradle.runQuietly(List.of("help"))).isFalse();
	}

	@Test
	void keepsProjectJvmArgsAndReplacesOnlyMemoryOptions() throws IOException {
		Files.writeString(this.project.resolve("gradle.properties"),
				"org.gradle.jvmargs=-Xms6g -Xmx8g -Dfile.encoding=UTF-8 --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED -XX:MaxMetaspaceSize=512m\n");

		GradleWrapperProcess gradle = new GradleWrapperProcess(this.project, null, null, Duration.ofMinutes(1), SCRIPTS,
				Logging.getLogger(GradleWrapperProcessTests.class));

		assertThat(gradle.jvmArgs())
			.startsWith("-Dfile.encoding=UTF-8 --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED -Xmx")
			.contains("-XX:MaxMetaspaceSize=1g")
			.doesNotContain("-Xms6g", "-Xmx8g", "512m");
	}

	@Test
	void usesExplicitJvmArgsAsGiven() throws IOException {
		Files.writeString(this.project.resolve("gradle.properties"), "org.gradle.jvmargs=-Dfile.encoding=UTF-8\n");

		GradleWrapperProcess gradle = new GradleWrapperProcess(this.project, null, "-Xmx64m", Duration.ofMinutes(1),
				SCRIPTS, Logging.getLogger(GradleWrapperProcessTests.class));

		assertThat(gradle.jvmArgs()).isEqualTo("-Xmx64m");
	}

	@Test
	void killsTargetGradleWhenRunnerIsInterrupted() throws Exception {
		Path pidFile = this.project.resolve("pid");
		gradlew("echo $$ > " + pidFile + "; sleep 30");
		GradleWrapperProcess gradle = new GradleWrapperProcess(this.project, null, "-Xmx64m", Duration.ZERO, SCRIPTS,
				Logging.getLogger(GradleWrapperProcessTests.class));
		Thread runner = new Thread(() -> {
			try {
				gradle.run(this.project.resolve("build.log"), List.of("build"));
			}
			catch (IllegalStateException expected) {
			}
		});
		runner.start();
		while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) {
			Thread.sleep(50);
		}
		long pid = Long.parseLong(Files.readString(pidFile).trim());

		runner.interrupt();
		runner.join(20_000);

		assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
	}

	private void gradlew(String body) throws IOException {
		Path script = this.project.resolve("gradlew");
		Files.writeString(script, "#!/bin/sh\n" + body + "\n");
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
	}

}
