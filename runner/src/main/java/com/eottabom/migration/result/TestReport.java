package com.eottabom.migration.result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eottabom.migration.guide.FailureHint;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 대상 프로젝트의 테스트 결과 XML 에서 모은 테스트 수, 실패 원인, properties-migrator 가 찍은 설정 키 변경.
 *
 * @param renamed 이름이 바뀐 설정 키 (키와 설정 출처가 같으면 한 번만)
 * @param unsupported 지원이 중단된 설정 키
 */
record TestReport(int total, List<TestFailure> failures, List<PropertyChange> renamed,
		List<PropertyChange> unsupported) {

	// Property source 가 여러 개일 수 있어 Boot 의 안내 문장이나 다음 블록까지를 본문으로 본다
	private static final Pattern MIGRATOR_BLOCK = Pattern.compile(
			"(?s)The use of configuration keys that (have been renamed|are no longer supported) was found in the environment:"
					+ "(.*?)(?=Each configuration key|Please refer to the release notes|The use of configuration keys that|\\z)");

	private static final Pattern PROPERTY_SOURCE = Pattern.compile("Property source '(.+)':\\s*$");

	private static final Pattern PROPERTY_KEY = Pattern.compile("Key: (\\S+)");

	private static final Pattern PROPERTY_REPLACEMENT = Pattern.compile("Replacement: (\\S+)");

	private static final Pattern FRAME_LOCATION = Pattern.compile("\\(([^()]+\\.(?:java|kt|groovy):\\d+)\\)");

	private static final int MAX_MESSAGE = 200;

	List<TestFailure> newFailures() {
		return this.failures.stream().filter((failure) -> !failure.existing()).toList();
	}

	List<TestFailure> existingFailures() {
		return this.failures.stream().filter(TestFailure::existing).toList();
	}

	/**
	 * @param baselineFailedTests 원본에서도 실패하던 테스트 id. 여기 있는 실패는 existing 으로 표시한다
	 * @param files 읽을 테스트 결과 파일
	 */
	static TestReport collect(Path projectDir, List<FailureHint> hints, Set<String> baselineFailedTests,
			List<Path> files) {
		int total = 0;
		List<TestFailure> failures = new ArrayList<>();
		Map<String, PropertyChange> renamed = new LinkedHashMap<>();
		Map<String, PropertyChange> unsupported = new LinkedHashMap<>();
		for (Path xml : files) {
			Element suite = TestResults.parse(xml).map((doc) -> doc.getDocumentElement()).orElse(null);
			if (suite == null) {
				continue;
			}
			total += TestResults.intAttribute(suite, "tests");
			NodeList cases = suite.getElementsByTagName("testcase");
			for (int i = 0; i < cases.getLength(); i++) {
				Element testcase = (Element) cases.item(i);
				Element failure = TestResults.firstChild(testcase, "failure");
				if (failure == null) {
					failure = TestResults.firstChild(testcase, "error");
				}
				if (failure != null) {
					failures.add(testFailure(testcase.getAttribute("classname"), testcase.getAttribute("name"),
							failure.getAttribute("message"), failure.getTextContent(), hints,
							baselineFailedTests.contains(TestResults.id(projectDir, xml, testcase))));
				}
			}
			Element out = TestResults.firstChild(suite, "system-out");
			if (out != null) {
				propertiesMigrator(out.getTextContent(), renamed, unsupported);
			}
		}
		return new TestReport(total, failures, new ArrayList<>(renamed.values()),
				new ArrayList<>(unsupported.values()));
	}

	/** PropertiesMigrationListener 출력의 Property source, Key, Replacement 줄을 모은다 */
	private static void propertiesMigrator(String systemOut, Map<String, PropertyChange> renamed,
			Map<String, PropertyChange> unsupported) {
		Matcher block = MIGRATOR_BLOCK.matcher(systemOut);
		while (block.find()) {
			Map<String, PropertyChange> bucket = block.group(1).contains("renamed") ? renamed : unsupported;
			String source = "";
			String current = null;
			for (String line : block.group(2).split("\n")) {
				Matcher sourceLine = PROPERTY_SOURCE.matcher(line);
				if (sourceLine.find()) {
					source = sourceLine.group(1);
				}
				Matcher keyLine = PROPERTY_KEY.matcher(line);
				if (keyLine.find()) {
					current = keyLine.group(1) + "|" + source;
					bucket.putIfAbsent(current, new PropertyChange(keyLine.group(1), null, source));
				}
				Matcher replacementLine = PROPERTY_REPLACEMENT.matcher(line);
				if (replacementLine.find() && current != null) {
					String replacement = replacementLine.group(1);
					bucket.computeIfPresent(current, (key, change) -> change.withReplacement(replacement));
				}
			}
		}
	}

	/** 스택트레이스의 가장 안쪽 예외(Caused by) 와 스택의 첫 프로젝트 코드 프레임, guides/ 의 실패 힌트 */
	static TestFailure testFailure(String classname, String name, @Nullable String message, @Nullable String stack,
			List<FailureHint> hints, boolean existing) {
		int dollar = classname.indexOf('$');
		String className = (dollar >= 0) ? classname.substring(0, dollar) : classname;
		String nested = (dollar >= 0) ? classname.substring(dollar + 1).replace("$", " > ") + " > " : "";
		List<String> lines = ((stack != null && !stack.isBlank()) ? stack : (message != null) ? message : "").lines()
			.toList();
		String root = lines.stream()
			.filter((line) -> line.startsWith("Caused by: "))
			.reduce((outer, inner) -> inner)
			.map((line) -> line.substring("Caused by: ".length()))
			.orElse(lines.isEmpty() ? ((message != null) ? message : "") : lines.get(0));
		int colon = root.indexOf(": ");
		String exception = ((colon > 0) ? root.substring(0, colon) : root).trim();
		String detail = (colon > 0) ? root.substring(colon + 2).trim() : "";
		String subject = exception + ": " + detail;
		String hint = hints.stream()
			.filter((candidate) -> candidate.matches(subject))
			.map(FailureHint::text)
			.findFirst()
			.orElse(null);
		return new TestFailure(className, nested + name, exception.substring(exception.lastIndexOf('.') + 1),
				(detail.length() > MAX_MESSAGE) ? detail.substring(0, MAX_MESSAGE) : detail,
				firstProjectFrame(className, lines), hint, existing);
	}

	/** 테스트 클래스 패키지의 앞 두 단계로 시작하는 첫 스택 프레임 위치. 테스트 코드가 아니라 같은 프로젝트의 다른 클래스일 수 있다 */
	private static @Nullable String firstProjectFrame(String className, List<String> stack) {
		String[] parts = className.split("\\.");
		String base = (parts.length >= 2) ? parts[0] + "." + parts[1] + "." : className + ".";
		return stack.stream()
			.map(String::trim)
			.filter((frame) -> frame.startsWith("at ") && frameClass(frame).startsWith(base))
			.findFirst()
			.map(FRAME_LOCATION::matcher)
			.filter(Matcher::find)
			.map((location) -> location.group(1))
			.orElse(null);
	}

	/**
	 * 스택 프레임의 클래스와 메서드. JDK 9+ 는 앞에 클래스로더와 모듈을 붙인다 (at app//demo.App.run(App.java:3), at
	 * java.base@21/java.lang.Thread.run(Thread.java:1583))
	 */
	private static String frameClass(String frame) {
		String head = frame.substring(3);
		int paren = head.indexOf('(');
		head = (paren >= 0) ? head.substring(0, paren) : head;
		return head.substring(head.lastIndexOf('/') + 1);
	}

	/**
	 * @param testName 중첩 클래스는 "Inner > 메서드" 로 이어 붙인다
	 * @param exception 가장 안쪽 예외의 단순 이름
	 * @param firstProjectFrame 스택의 첫 프로젝트 코드 프레임 (찾지 못하면 null)
	 * @param hint guides/ 의 실패 힌트 (없으면 null)
	 * @param existing 원본에서도 실패하던 테스트다
	 */
	record TestFailure(String className, String testName, String exception, String message,
			@Nullable String firstProjectFrame, @Nullable String hint, boolean existing) {
	}

	/**
	 * @param replacement 새 키 (지원 중단이면 null)
	 * @param source 설정 출처 (application.yml 등)
	 */
	record PropertyChange(String key, @Nullable String replacement, String source) {

		PropertyChange withReplacement(String replacement) {
			return new PropertyChange(this.key, replacement, this.source);
		}

	}

}
