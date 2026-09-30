package com.eottabom.migration.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.eottabom.migration.io.Processes;
import org.jspecify.annotations.Nullable;

/**
 * 대상 Gradle 을 띄울 JDK 를 고른다. toolchain 이 없는 모듈은 Gradle 을 띄운 JVM 으로 컴파일되므로 프로젝트가 선언한 버전을
 * 찾는다. JAVA_HOME_{N} 환경 변수, macOS java_home, 흔한 설치 디렉토리 순으로 찾고 없으면 현재 JAVA_HOME 을 쓴다.
 */
public final class JdkLocator {

	private static final Path MAC_JAVA_HOME = Path.of("/usr/libexec/java_home");

	private static final Pattern RELEASE_VERSION = Pattern.compile("(?m)^JAVA_VERSION=\"(?:1\\.)?(\\d+)");

	/** 그 버전의 JDK 가 설치된 경로 */
	public Optional<String> find(int version) {
		return fromEnvironment(version).or(() -> fromMacJavaHome(version)).or(() -> fromInstallDirectories(version));
	}

	/** JDK 홈의 major 버전. null 이면 현재 JAVA_HOME, 모르면 빈 값 */
	public static Optional<Integer> versionOf(@Nullable String home) {
		String dir = (home != null) ? home : System.getenv("JAVA_HOME");
		return (dir != null) ? majorVersion(Path.of(dir)) : Optional.empty();
	}

	private static Optional<String> fromEnvironment(int version) {
		for (String name : List.of("JAVA_HOME_" + version + "_X64", "JAVA_HOME_" + version + "_AARCH64",
				"JAVA_HOME_" + version + "_ARM64", "JAVA_HOME_" + version)) {
			String home = System.getenv(name);
			if (home != null && Files.isDirectory(Path.of(home))) {
				return Optional.of(home);
			}
		}
		return Optional.empty();
	}

	private static Optional<String> fromMacJavaHome(int version) {
		if (!Files.isExecutable(MAC_JAVA_HOME)) {
			return Optional.empty();
		}
		Path cwd = Path.of(System.getProperty("user.home"));
		// java_home -v 17 은 17 이 없으면 다른 버전을 돌려주므로 설치 목록에 해당 major 가 있을 때만 쓴다
		String installed = Processes.captureWithErrors(cwd, MAC_JAVA_HOME.toString(), "-V");
		if (installed == null || !Pattern.compile("(?m)^ +" + version + "[ .]").matcher(installed).find()) {
			return Optional.empty();
		}
		String home = Processes.capture(cwd, MAC_JAVA_HOME.toString(), "-v", String.valueOf(version));
		return Optional.ofNullable(home).map(String::trim).filter((path) -> !path.isEmpty());
	}

	static Optional<String> fromInstallDirectories(int version) {
		String userHome = System.getProperty("user.home");
		List<Path> roots = List.of(Path.of("/usr/lib/jvm"), Path.of("/Library/Java/JavaVirtualMachines"),
				Path.of(userHome, "Library/Java/JavaVirtualMachines"), Path.of(userHome, ".sdkman/candidates/java"),
				Path.of(userHome, ".gradle/jdks"), Path.of(userHome, ".jdks"), Path.of(userHome, ".asdf/installs/java"),
				Path.of("C:\\Program Files\\Java"), Path.of("C:\\Program Files\\Eclipse Adoptium"),
				Path.of("C:\\Program Files\\Zulu"), Path.of("C:\\Program Files\\Amazon Corretto"));
		for (Path root : roots) {
			for (Path home : candidates(root)) {
				if (majorVersion(home).filter((major) -> major == version).isPresent()) {
					return Optional.of(home.toString());
				}
			}
		}
		return Optional.empty();
	}

	/** root 아래의 JDK 홈 후보. macOS 번들은 Contents/Home 이 실제 홈이다. */
	private static List<Path> candidates(Path root) {
		List<Path> homes = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			return homes;
		}
		try (Stream<Path> children = Files.list(root)) {
			for (Path child : children.sorted().toList()) {
				Path bundle = child.resolve("Contents/Home");
				homes.add(Files.isDirectory(bundle) ? bundle : child);
			}
		}
		catch (IOException ignored) {
		}
		return homes;
	}

	static Optional<Integer> majorVersion(Path home) {
		Path release = home.resolve("release");
		if (!Files.isRegularFile(release)) {
			return Optional.empty();
		}
		try {
			Matcher javaVersion = RELEASE_VERSION.matcher(Files.readString(release));
			return javaVersion.find() ? Optional.of(Integer.parseInt(javaVersion.group(1))) : Optional.empty();
		}
		catch (IOException ex) {
			return Optional.empty();
		}
	}

}
