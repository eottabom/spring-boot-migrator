package com.eottabom.migration.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eottabom.migration.io.TextFiles;

/**
 * --continue 로 돌린 빌드에서 실패한 태스크 경로. verify.init.gradle 이 남긴 파일을 먼저 읽고, 없으면(init script 가
 * 기록하지 못한 Gradle 버전 등) 로그에서 찾는다. 둘 다 없으면 빈 집합이다.
 */
public final class FailedTasks {

	private static final Pattern EXECUTION_FAILED = Pattern.compile("Execution failed for task '([^']+)'");

	private static final Pattern COMPILE_TASK = Pattern.compile(":compile(Test)?(Java|Groovy|Kotlin)$");

	private FailedTasks() {
	}

	public static Set<String> read(Path failedTasksFile, Path log) {
		if (Files.exists(failedTasksFile)) {
			return new TreeSet<>(
					TextFiles.readLines(failedTasksFile).stream().filter((line) -> !line.isBlank()).toList());
		}
		Set<String> tasks = new TreeSet<>();
		Matcher failed = EXECUTION_FAILED.matcher(TextFiles.read(log));
		while (failed.find()) {
			tasks.add(failed.group(1));
		}
		return tasks;
	}

	public static boolean anyCompileTask(Set<String> tasks) {
		return tasks.stream().anyMatch((task) -> COMPILE_TASK.matcher(task).find());
	}

}
