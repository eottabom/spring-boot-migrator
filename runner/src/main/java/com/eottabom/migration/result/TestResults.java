package com.eottabom.migration.result;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * build/test-results 집계. verify.init.gradle 이 ignoreFailures 를 켜서 build 성공만으로는 알 수 없다. 큰
 * 저장소를 위해 build 안에서는 test-results 만 본다.
 */
public final class TestResults {

	private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", ".gradle", ".idea",
			".spring-boot-migrator");

	/** 쓰는 중인 결과 파일을 다시 읽기 전에 기다리는 시간 */
	private static final long REREAD_DELAY_MS = 200;

	private TestResults() {
	}

	/** 결과 파일을 읽어 테스트 수와 실패한 테스트를 모은다. 읽지 못한 파일은 따로 둔다 */
	public static Results read(Path projectDir, List<Path> files) {
		int total = 0;
		Set<String> failed = new TreeSet<>();
		Set<String> passed = new TreeSet<>();
		Set<String> skipped = new TreeSet<>();
		List<Path> unreadable = new ArrayList<>();
		for (Path xml : files) {
			Optional<Document> doc = parse(xml);
			if (doc.isEmpty()) {
				unreadable.add(xml);
				continue;
			}
			total += intAttribute(doc.get().getDocumentElement(), "tests");
			NodeList cases = doc.get().getElementsByTagName("testcase");
			for (int i = 0; i < cases.getLength(); i++) {
				Element testcase = (Element) cases.item(i);
				if (firstChild(testcase, "failure") != null || firstChild(testcase, "error") != null) {
					failed.add(id(projectDir, xml, testcase));
				}
				else if (firstChild(testcase, "skipped") != null) {
					skipped.add(id(projectDir, xml, testcase));
				}
				else {
					passed.add(id(projectDir, xml, testcase));
				}
			}
		}
		passed.removeAll(failed);
		passed.removeAll(skipped);
		return new Results(total, failed, List.copyOf(unreadable), Set.copyOf(passed));
	}

	/**
	 * 모듈을 포함한 테스트 id. 여러 모듈에 같은 이름의 테스트가 있어도 구분된다. 예) api:com.example.UserTest#works, 루트
	 * 모듈은 com.example.UserTest#works
	 */
	static String id(Path projectDir, Path xml, Element testcase) {
		String module = module(projectDir, xml);
		return (module.isEmpty() ? "" : module + ":") + testcase.getAttribute("classname") + "#"
				+ testcase.getAttribute("name");
	}

	/** id 에서 모듈을 뺀 테스트 클래스 */
	public static String testClass(String id) {
		// 테스트 이름(# 뒤)에 ':' 가 들어갈 수 있다 (ex. 파라미터 테스트 "[1] given=:CC01") → # 앞에서 모듈을 뗀다
		int hash = id.indexOf('#');
		String moduleAndClass = (hash >= 0) ? id.substring(0, hash) : id;
		return moduleAndClass.substring(moduleAndClass.lastIndexOf(':') + 1);
	}

	/** 결과 XML 이 속한 모듈의 프로젝트 기준 경로 (build 디렉토리의 부모). 루트 모듈은 빈 문자열 */
	static String module(Path projectDir, Path xml) {
		Path results = testResultsDir(xml);
		if (results == null) {
			return "";
		}
		return projectDir.relativize(Objects.requireNonNull(results.getParent()).getParent())
			.toString()
			.replace('\\', '/');
	}

	/** 프로젝트 안의 모든 결과 XML 과 그 상태 */
	static Map<Path, Stamp> all(Path projectDir) {
		Map<Path, Stamp> found = new HashMap<>();
		try {
			Files.walkFileTree(projectDir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					String name = fileName(dir);
					if (SKIP_DIRS.contains(name)) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					Path parent = dir.getParent();
					return (parent != null && "build".equals(fileName(parent)) && !name.equals("test-results"))
							? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (isResultFile(file)) {
						found.put(file, new Stamp(attrs.lastModifiedTime(), attrs.size()));
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return found;
	}

	/** 디렉토리 바로 아래의 TEST-*.xml (관례 밖의 결과 디렉토리) */
	static Map<Path, Stamp> resultFilesIn(Path dir) {
		Map<Path, Stamp> found = new HashMap<>();
		if (!Files.isDirectory(dir)) {
			return found;
		}
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.toList()) {
				String name = fileName(file);
				if (name.startsWith("TEST-") && name.endsWith(".xml")) {
					BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
					found.put(file, new Stamp(attrs.lastModifiedTime(), attrs.size()));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return found;
	}

	/** 쓰는 중이라 읽지 못하면 잠깐 기다렸다가 한 번 더 읽는다. 그래도 안 되면 건너뛴다 */
	static Optional<Document> parse(Path xml) {
		DocumentBuilder parser = xmlParser();
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				return Optional.of(parser.parse(xml.toFile()));
			}
			catch (Exception ex) {
				sleep(REREAD_DELAY_MS);
			}
		}
		return Optional.empty();
	}

	static @Nullable Element firstChild(Element parent, String tag) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && e.getTagName().equals(tag)) {
				return e;
			}
		}
		return null;
	}

	static DocumentBuilder xmlParser() {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setExpandEntityReferences(false);
			return factory.newDocumentBuilder();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	static int intAttribute(Element element, String name) {
		String value = element.getAttribute(name);
		return value.isEmpty() ? 0 : Integer.parseInt(value);
	}

	/** build/test-results 아래의 TEST-*.xml */
	private static boolean isResultFile(Path file) {
		String name = fileName(file);
		return name.startsWith("TEST-") && name.endsWith(".xml") && testResultsDir(file) != null;
	}

	/** 파일을 감싸는 가장 가까운 build/test-results 디렉토리 */
	private static @Nullable Path testResultsDir(Path file) {
		for (Path p = file.getParent(); p != null && p.getParent() != null; p = p.getParent()) {
			if ("test-results".equals(fileName(p)) && "build".equals(fileName(p.getParent()))) {
				return p;
			}
		}
		return null;
	}

	private static String fileName(Path path) {
		return (path.getFileName() == null) ? "" : path.getFileName().toString();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 빌드 전의 결과 파일 상태. 빌드 뒤에 새로 생기거나 바뀐 파일만 그 빌드의 결과로 본다. 따로 빌드되는 중첩 프로젝트에 남은 예전 결과는 바뀌지
	 * 않아서 섞이지 않는다.
	 */
	public record Snapshot(Path projectDir, Map<Path, Stamp> files) {

		public static Snapshot take(Path projectDir) {
			return new Snapshot(projectDir, all(projectDir));
		}

		/**
		 * @param reportDirs 빌드가 알려 준 결과 XML 디렉토리 (verify.init.gradle). build/test-results
		 * 관례 밖에 둔 결과도 찾는다
		 */
		public List<Path> changedFiles(Collection<Path> reportDirs) {
			Map<Path, Stamp> now = all(this.projectDir);
			reportDirs.forEach((dir) -> now.putAll(resultFilesIn(dir)));
			return now.entrySet()
				.stream()
				.filter((entry) -> !entry.getValue().equals(this.files.get(entry.getKey())))
				.map(Map.Entry::getKey)
				.sorted()
				.toList();
		}

	}

	/** 수정 시각이 초 단위로만 기록되는 파일 시스템이 있어 크기도 함께 본다 */
	public record Stamp(FileTime modified, long size) {
	}

	/**
	 * @param failedTests 실패하거나 에러가 난 테스트의 id ({@link #id})
	 * @param unreadable 끝까지 읽지 못한 결과 파일. 있으면 결과가 실제보다 적을 수 있다
	 */
	public record Results(int total, Set<String> failedTests, List<Path> unreadable, Set<String> passedTests) {

		public int failed() {
			return this.failedTests.size();
		}

		public boolean complete() {
			return this.unreadable.isEmpty();
		}

		/** 읽지 못한 결과에 더한다 (테스트 태스크가 돌았는데 결과 XML 이 없는 디렉토리) */
		public Results withUnreadable(Collection<Path> more) {
			if (more.isEmpty()) {
				return this;
			}
			List<Path> all = new ArrayList<>(this.unreadable);
			all.addAll(more);
			return new Results(this.total, this.failedTests, List.copyOf(all), this.passedTests);
		}

	}

}
