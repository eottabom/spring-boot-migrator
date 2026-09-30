package com.eottabom.migration.gradle;

import java.io.IOException;
import java.io.Reader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 대상 Gradle 데몬의 JVM 옵션. 명령행 -Dorg.gradle.jvmargs 는 gradle.properties 의 값을 통째로 대신하므로,
 * file.encoding 이나 --add-exports 같은 프로젝트 옵션을 잃지 않게 프로젝트 값에서 메모리 옵션만 러너 값으로 바꾼다.
 */
final class GradleJvmArgs {

	/** 러너가 정하는 메모리 옵션. -Xms 도 뺀다. 프로젝트의 -Xms 가 러너의 -Xmx 보다 크면 데몬이 뜨지 않는다 */
	private static final List<String> MEMORY_OPTIONS = List.of("-Xmx", "-Xms", "-XX:MaxMetaspaceSize=");

	private GradleJvmArgs() {
	}

	/** 설정 파일에 값을 주지 않았을 때 쓰는 옵션 */
	static String forProject(Path projectDir) {
		return merge(declaredIn(projectDir), runnerMemory());
	}

	/** 대상 프로젝트 gradle.properties 의 org.gradle.jvmargs (없으면 빈 문자열) */
	static String declaredIn(Path projectDir) {
		Path file = projectDir.resolve("gradle.properties");
		if (!Files.isRegularFile(file)) {
			return "";
		}
		Properties properties = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
			properties.load(in);
		}
		catch (IOException ex) {
			return "";
		}
		return properties.getProperty("org.gradle.jvmargs", "").trim();
	}

	/** project 의 옵션에서 메모리 옵션만 runner 의 값으로 바꾼다 */
	static String merge(String project, String runner) {
		List<String> merged = new ArrayList<>();
		for (String option : project.split("\\s+")) {
			if (!option.isEmpty() && MEMORY_OPTIONS.stream().noneMatch(option::startsWith)) {
				merged.add(option);
			}
		}
		merged.addAll(List.of(runner.split("\\s+")));
		return String.join(" ", merged);
	}

	/**
	 * OpenRewrite 는 전체 LST 를 메모리에 올린다. 최대 6g 로 하되 CI 컨테이너에서 죽지 않도록 장비 메모리의 절반을 넘기지 않는다.
	 */
	static String runnerMemory() {
		long totalMb = 8192;
		if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
			totalMb = os.getTotalMemorySize() / (1024 * 1024);
		}
		long heapMb = Math.max(1024, Math.min(6144, totalMb / 2));
		return "-Xmx" + heapMb + "m -XX:MaxMetaspaceSize=1g";
	}

}
