package com.eottabom.migration.project;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.eottabom.migration.version.Versions;
import org.jspecify.annotations.Nullable;

/** 대상 프로젝트의 빌드 파일만 읽어 {@link ProjectState} 을 만든다. 대상 Gradle 을 띄우지 않는다. */
public final class ProjectInspector {

	// id 'org.springframework.boot' version '3.4.5' / id("org.springframework.boot")
	// version "3.4.5"
	// / classpath 'org.springframework.boot:spring-boot-gradle-plugin:2.7.18'
	private static final Pattern BOOT_PLUGIN = Pattern
		.compile("org\\.springframework\\.boot['\"]?\\)?\\s+version\\s*\\(?\\s*['\"]([0-9][0-9.]*)"
				+ "|spring-boot-gradle-plugin:([0-9][0-9.]*)");

	// springBootVersion = '2.7.18' / set('springBootVersion', '2.7.18') /
	// extra["springBootVersion"] = "2.7.18"
	private static final Pattern BOOT_PROPERTY = Pattern
		.compile("springBootVersion['\"]?]?\\s*,?\\s*=?\\s*['\"]([0-9][0-9.]*)");

	// 주석 처리한 선언은 보지 않는다. 문자열을 먼저 맞춰 건너뛴다 ('**/generated/**' 의 /* 는 주석이 아니다).
	// // 앞에 공백이나 줄 시작이 있어야 한다 (따옴표 밖의 https:// 는 주석이 아니다)
	private static final Pattern COMMENTS = Pattern.compile(
			"'(?:\\\\.|[^'\\\\\\n])*'|\"(?:\\\\.|[^\"\\\\\\n])*\"|/\\*(?s:.*?)\\*/|(?:^|(?<=\\s))//[^\\n]*",
			Pattern.MULTILINE);

	private static final Pattern MAJOR_MINOR_PATCH = Pattern.compile("[0-9]+\\.[0-9]+(\\.[0-9]+)?");

	private static final Pattern GRADLE_DIST = Pattern.compile("gradle-([0-9][0-9.]*[0-9])-(bin|all)");

	private static final Pattern TOOLCHAIN = Pattern
		.compile("JavaLanguageVersion\\.of\\((\\d+)\\)|VERSION_(?:1_)?(\\d+)");

	// JavaLanguageVersion.of(libs.versions.java.get().toInteger()), libs 대신 다른 catalog
	// 이름도 된다
	private static final Pattern TOOLCHAIN_CATALOG = Pattern
		.compile("JavaLanguageVersion\\.of\\(\\s*(\\w+)\\.versions\\.([\\w.]+?)\\.get\\(\\)");

	private static final Pattern SOURCE_COMPATIBILITY = Pattern
		.compile("sourceCompatibility *= *['\"]?(?:1\\.)?(\\d+)");

	private static final Set<String> SKIP_DIRS = Set.of(".git", ".gradle", "build", "node_modules",
			".spring-boot-migrator");

	public ProjectState inspect(Path dir) {
		List<String> buildFiles = readAll(buildGradleFiles(dir));
		VersionCatalog catalog = VersionCatalog.read(dir);
		List<Integer> toolchains = numbers(TOOLCHAIN, buildFiles);
		toolchains.addAll(catalogJava(catalog, buildFiles));
		List<Integer> declared = new ArrayList<>(toolchains);
		declared.addAll(numbers(SOURCE_COMPATIBILITY, buildFiles));
		return new ProjectState(dir, bootVersion(dir, catalog, buildFiles), gradleVersion(dir),
				declared.stream().min(Integer::compare).orElse(null),
				toolchains.stream().max(Integer::compare).orElse(null));
	}

	/** rewriteRun 뒤에 다시 읽는 용도. */
	public @Nullable String bootVersion(Path dir) {
		return bootVersion(dir, VersionCatalog.read(dir), readAll(buildGradleFiles(dir)));
	}

	/** 루트 플러그인 버전, springBootVersion 속성, version catalog, 서브프로젝트 플러그인(가장 낮은 것) 순으로 찾는다 */
	private @Nullable String bootVersion(Path dir, VersionCatalog catalog, List<String> allBuildFiles) {
		// settings 의 pluginManagement { plugins { } } 에서 버전을 정하는 프로젝트도 있다
		List<Path> rootBuildFiles = List.of(dir.resolve("build.gradle"), dir.resolve("build.gradle.kts"),
				dir.resolve("settings.gradle"), dir.resolve("settings.gradle.kts"));
		List<String> rootContents = readAll(rootBuildFiles);
		return first(BOOT_PLUGIN, rootContents).or(() -> first(BOOT_PROPERTY, rootContents))
			.or(() -> property(dir.resolve("gradle.properties"), "springBootVersion"))
			.or(() -> catalog.plugin("org.springframework.boot"))
			.or(() -> lowest(BOOT_PLUGIN, allBuildFiles))
			.map((declared) -> {
				Matcher version = MAJOR_MINOR_PATCH.matcher(declared);
				return version.find() ? version.group() : null;
			})
			.orElse(null);
	}

	private static List<Integer> catalogJava(VersionCatalog catalog, List<String> buildFiles) {
		List<Integer> found = new ArrayList<>();
		for (String content : buildFiles) {
			Matcher toolchain = TOOLCHAIN_CATALOG.matcher(content);
			while (toolchain.find()) {
				catalog.version(toolchain.group(1), toolchain.group(2))
					.filter((version) -> version.matches("\\d+"))
					.map(Integer::parseInt)
					.ifPresent(found::add);
			}
		}
		return found;
	}

	private static Optional<String> lowest(Pattern pattern, List<String> contents) {
		List<String> all = new ArrayList<>();
		for (String content : contents) {
			Matcher matcher = pattern.matcher(content);
			while (matcher.find()) {
				all.add(group(matcher));
			}
		}
		return all.stream().min(Versions::compare);
	}

	/** gradle.properties 는 따옴표 없이 쓴다 (springBootVersion=2.7.18) */
	private static Optional<String> property(Path file, String name) {
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		Properties properties = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
			properties.load(in);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return Optional.ofNullable(properties.getProperty(name))
			.map((value) -> value.trim().replaceAll("^['\"]|['\"]$", ""))
			.filter((value) -> value.matches("[0-9][0-9.]*.*"));
	}

	/** 대안이 여럿인 패턴에서 값이 잡힌 그룹 */
	private static String group(Matcher matcher) {
		for (int index = 1; index <= matcher.groupCount(); index++) {
			if (matcher.group(index) != null) {
				return matcher.group(index);
			}
		}
		throw new IllegalStateException(matcher.pattern().pattern());
	}

	public @Nullable String gradleVersion(Path dir) {
		return first(GRADLE_DIST, readAll(List.of(dir.resolve("gradle/wrapper/gradle-wrapper.properties"))))
			.orElse(null);
	}

	private static Optional<String> first(Pattern pattern, List<String> contents) {
		for (String content : contents) {
			Matcher matcher = pattern.matcher(content);
			if (matcher.find()) {
				return Optional.of(group(matcher));
			}
		}
		return Optional.empty();
	}

	private static List<Integer> numbers(Pattern pattern, List<String> contents) {
		List<Integer> found = new ArrayList<>();
		for (String content : contents) {
			Matcher matcher = pattern.matcher(content);
			while (matcher.find()) {
				for (int index = 1; index <= matcher.groupCount(); index++) {
					if (matcher.group(index) != null) {
						found.add(Integer.parseInt(matcher.group(index)));
					}
				}
			}
		}
		return found;
	}

	private static List<Path> buildGradleFiles(Path dir) {
		List<Path> files = new ArrayList<>();
		collect(dir, files);
		return files;
	}

	private static void collect(Path dir, List<Path> files) {
		try (Stream<Path> children = Files.list(dir)) {
			for (Path child : children.toList()) {
				String name = child.getFileName().toString();
				if (Files.isDirectory(child)) {
					if (!SKIP_DIRS.contains(name)) {
						collect(child, files);
					}
				}
				else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
					files.add(child);
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 주석만 지우고 문자열은 그대로 둔다 */
	static String stripComments(String source) {
		return COMMENTS.matcher(source).replaceAll((match) -> {
			char first = match.group().charAt(0);
			return (first == '\'' || first == '"') ? Matcher.quoteReplacement(match.group()) : "";
		});
	}

	private static List<String> readAll(List<Path> files) {
		List<String> contents = new ArrayList<>();
		for (Path file : files) {
			if (Files.isRegularFile(file)) {
				try {
					contents.add(stripComments(Files.readString(file)));
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}
		}
		return contents;
	}

}
