package com.eottabom.migration.pipeline;

import java.util.Optional;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.project.JdkLocator;
import com.eottabom.migration.project.ProjectState;
import org.jspecify.annotations.Nullable;

/**
 * 대상 Gradle 을 띄울 JDK. toolchain 이 있으면 그 버전, 없으면 Gradle 을 띄운 JVM 으로 컴파일되므로 선언된 Java 버전보다
 * 낮은 JAVA_HOME 은 쓰지 않는다. stage 가 Java 버전을 올리면 다시 고른다.
 */
record JdkSelection(JdkLocator locator, RunnerConsole console) {

	@Nullable String javaHome(ProjectState project, boolean keepJavaHome) {
		String current = System.getenv("JAVA_HOME");
		if (keepJavaHome) {
			return current;
		}
		if (project.toolchainJava() != null) {
			return found(project.toolchainJava(), current);
		}
		Integer declared = project.javaVersion();
		if (declared == null) {
			return current;
		}
		Optional<Integer> running = JdkLocator.versionOf(current);
		if (running.isPresent() && running.get() >= declared) {
			return current;
		}
		return found(declared, current);
	}

	private @Nullable String found(int version, @Nullable String current) {
		Optional<String> home = this.locator.find(version);
		if (home.isEmpty()) {
			this.console.error("JDK " + version + " 을 찾지 못해 현재 JAVA_HOME(" + RunnerConsole.orDefault(current)
					+ ")으로 대상 Gradle 을 띄울게요. JAVA_HOME_" + version + " 환경 변수로 경로를 알려 줄 수 있어요");
		}
		return home.orElse(current);
	}

}
