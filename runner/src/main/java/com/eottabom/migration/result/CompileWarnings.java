package com.eottabom.migration.result;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eottabom.migration.io.TextFiles;

/**
 * 컴파일 로그의 [removal] / [deprecation] 경고. 같은 메시지는 위치를 모아 한 번만 두고, 위치가 많은 것부터 둔다.
 */
public record CompileWarnings(List<ApiWarning> removal, List<ApiWarning> deprecation) {

	/**
	 * 경로는 공백이나 Windows 드라이브 문자(C:\)를 포함할 수 있고, "warning:" 은 JVM 로케일에 따라 번역된다 (경고:). 로케일과
	 * 상관없이 영어로 찍히는 [removal] / [deprecation] 태그를 기준으로 잡는다.
	 */
	private static final Pattern WARNING = Pattern
		.compile("^(.+?\\.(?:java|kt|groovy)):(\\d+): [^\\[]*\\[(removal|deprecation)\\] (.*)$");

	/**
	 * @param projectDir 경고 위치를 이 디렉토리 기준 상대 경로로 줄인다
	 */
	public static CompileWarnings collect(Path compileLog, Path projectDir) {
		String root = slashes(projectDir.toAbsolutePath().toString()) + "/";
		Map<String, Set<String>> removal = new LinkedHashMap<>();
		Map<String, Set<String>> deprecation = new LinkedHashMap<>();
		for (String line : TextFiles.readLines(compileLog)) {
			Matcher m = WARNING.matcher(line);
			if (m.find()) {
				String path = slashes(m.group(1));
				String file = path.startsWith(root) ? path.substring(root.length()) : path;
				(m.group(3).equals("removal") ? removal : deprecation)
					.computeIfAbsent(m.group(4), (k) -> new LinkedHashSet<>())
					.add(file + ":" + m.group(2));
			}
		}
		return new CompileWarnings(sorted(removal), sorted(deprecation));
	}

	private static List<ApiWarning> sorted(Map<String, Set<String>> byMessage) {
		return byMessage.entrySet()
			.stream()
			.map((e) -> new ApiWarning(e.getKey(), List.copyOf(e.getValue())))
			.sorted(Comparator.comparingInt((ApiWarning w) -> w.locations().size()).reversed())
			.toList();
	}

	private static String slashes(String path) {
		return path.replace('\\', '/');
	}

	/**
	 * @param locations 파일:줄 (프로젝트 기준 상대 경로)
	 */
	public record ApiWarning(String message, List<String> locations) {
	}

}
