package com.eottabom.migration.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * gradle/*.versions.toml 에서 버전을 찾는 데 필요한 만큼만 읽는다 ([versions] 와 [plugins] 의 한 줄 항목).
 *
 * <pre>
 * [versions]
 * spring-boot = "4.0.7"
 * [plugins]
 * spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
 * </pre>
 */
record VersionCatalog(Map<String, String> versions, Map<String, String> plugins) {

	private static final String CATALOG_SUFFIX = ".versions.toml";

	private static final Pattern SECTION = Pattern.compile("^\\s*\\[([\\w.-]+)]\\s*$");

	private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([\\w.-]+)\\s*=\\s*(.*)$");

	/** "value" 또는 inline table 의 key = "value". TOML 의 작은따옴표 문자열('value')도 받는다 */
	private static final Pattern ATTRIBUTE = Pattern.compile("(?:([\\w.]+)\\s*=\\s*)?(?:\"([^\"]*)\"|'([^']*)')");

	/** version = { ref = "x" } 는 version.ref = "x" 와 같은 뜻이다 */
	private static final Pattern VERSION_REF_TABLE = Pattern
		.compile("version\\s*=\\s*\\{\\s*ref\\s*=\\s*(\"[^\"]*\"|'[^']*')\\s*}");

	/** gradle/*.versions.toml 을 모두 읽는다. 같은 플러그인이 여러 catalog 에 있으면 libs 를 먼저 본다 */
	static VersionCatalog read(Path projectDir) {
		Map<String, String> versions = new HashMap<>();
		Map<String, String> plugins = new HashMap<>();
		for (Path file : catalogFiles(projectDir.resolve("gradle"))) {
			readCatalog(file, versions, plugins);
		}
		return new VersionCatalog(versions, plugins);
	}

	/** libs.versions.toml 을 먼저, 나머지는 이름순 */
	private static List<Path> catalogFiles(Path gradleDir) {
		if (!Files.isDirectory(gradleDir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(gradleDir)) {
			return files.filter((file) -> file.getFileName().toString().endsWith(CATALOG_SUFFIX))
				.sorted(Comparator.comparing((Path file) -> !catalogName(file).equals("libs"))
					.thenComparing(Path::toString))
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String catalogName(Path file) {
		String fileName = file.getFileName().toString();
		return fileName.substring(0, fileName.length() - CATALOG_SUFFIX.length());
	}

	private static void readCatalog(Path file, Map<String, String> versions, Map<String, String> plugins) {
		String catalog = catalogName(file);
		Map<String, String> catalogVersions = new HashMap<>();
		Map<String, Map<String, String>> pluginTables = new HashMap<>();
		String section = "";
		for (String line : readLines(file)) {
			String content = stripComment(line);
			Matcher header = SECTION.matcher(content);
			Matcher entry = KEY_VALUE.matcher(content);
			if (header.matches()) {
				section = header.group(1);
			}
			else if (entry.matches() && section.equals("versions")) {
				String version = attributes(entry.group(2)).get("");
				if (version != null) {
					catalogVersions.put(normalize(entry.group(1)), version);
					versions.put(key(catalog, entry.group(1)), version);
				}
			}
			else if (entry.matches() && section.equals("plugins")) {
				Map<String, String> table = attributes(entry.group(2));
				String shorthand = table.get("");
				if (table.containsKey("id")) {
					pluginTables.put(table.get("id"), table);
				}
				else if (shorthand != null && shorthand.contains(":")) {
					// spring-boot = "org.springframework.boot:3.4.5"
					int colon = shorthand.lastIndexOf(':');
					pluginTables.put(shorthand.substring(0, colon), Map.of("version", shorthand.substring(colon + 1)));
				}
			}
		}
		pluginTables.forEach((id, table) -> {
			String ref = table.get("version.ref");
			String version = (ref != null) ? catalogVersions.get(normalize(ref)) : table.get("version");
			if (version != null) {
				plugins.putIfAbsent(id, version);
			}
		});
	}

	/** 따옴표 값의 key 와 값. 키 없는 값("4.0.7")은 빈 키로 둔다 */
	private static Map<String, String> attributes(String value) {
		Map<String, String> attributes = new HashMap<>();
		Matcher matcher = ATTRIBUTE.matcher(VERSION_REF_TABLE.matcher(value).replaceAll("version.ref = $1"));
		while (matcher.find()) {
			String text = (matcher.group(2) != null) ? matcher.group(2) : matcher.group(3);
			attributes.putIfAbsent((matcher.group(1) != null) ? matcher.group(1) : "", text);
		}
		return attributes;
	}

	/** 따옴표 밖의 # 부터 줄 끝까지 지운다 (문자열 안의 # 은 값이다) */
	static String stripComment(String line) {
		char quote = 0;
		for (int index = 0; index < line.length(); index++) {
			char character = line.charAt(index);
			if (quote != 0) {
				if (character == quote) {
					quote = 0;
				}
			}
			else if (character == '"' || character == '\'') {
				quote = character;
			}
			else if (character == '#') {
				return line.substring(0, index);
			}
		}
		return line;
	}

	private static List<String> readLines(Path file) {
		try {
			return Files.readAllLines(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	Optional<String> plugin(String id) {
		return Optional.ofNullable(this.plugins.get(id));
	}

	/** libs.versions.java 처럼 빌드 스크립트의 접근자로 찾는다. catalog 는 접근자 앞부분(libs, deps 등) */
	Optional<String> version(String catalog, String accessor) {
		return Optional.ofNullable(this.versions.get(key(catalog, accessor)));
	}

	private static String key(String catalog, String accessor) {
		return catalog + ":" + normalize(accessor);
	}

	private static String normalize(String key) {
		return key.replaceAll("[-_.]", ".").toLowerCase(Locale.ROOT);
	}
}
