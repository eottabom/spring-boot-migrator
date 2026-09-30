package com.eottabom.migration.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 버전 비교. 숫자 부분을 먼저 비교하고, 같으면 수식어를 Maven 순서로 비교한다. alpha &lt; beta &lt; milestone(M) &lt;
 * rc/cr &lt; snapshot &lt; 정식(없음, Final, RELEASE, GA, jre 등) &lt; sp 예) 3.0.0-RC1 &lt;
 * 3.0.0 = 3.0.0.Final = 3.0.0.RELEASE, 7.0.0.Beta2 &lt; 7.0.0.CR1 &lt; 7.0.0
 */
public final class Versions {

	private static final Pattern NUMERIC_PREFIX = Pattern.compile("^(\\d+(?:\\.\\d+)*)(.*)$");

	private static final Pattern QUALIFIER = Pattern.compile("^([a-z]+)(\\d*)");

	private Versions() {
	}

	public static int compare(String left, String right) {
		Parsed leftVersion = parse(left);
		Parsed rightVersion = parse(right);
		int segments = Math.max(leftVersion.numbers().size(), rightVersion.numbers().size());
		for (int index = 0; index < segments; index++) {
			int bySegment = Long.compare(leftVersion.number(index), rightVersion.number(index));
			if (bySegment != 0) {
				return bySegment;
			}
		}
		int byQualifier = leftVersion.qualifier().compareTo(rightVersion.qualifier());
		return (byQualifier != 0) ? byQualifier
				: Long.compare(leftVersion.qualifierNumber(), rightVersion.qualifierNumber());
	}

	public static int major(String version) {
		return (int) Math.min(Integer.MAX_VALUE, parse(version).number(0));
	}

	private static Parsed parse(String version) {
		String trimmed = version.trim();
		Matcher numeric = NUMERIC_PREFIX.matcher(trimmed);
		// 날짜형 세그먼트(6.7.0.202309050840-r)는 int 를 넘는다
		List<Long> numbers = new ArrayList<>();
		String rest = trimmed;
		if (numeric.matches()) {
			for (String token : numeric.group(1).split("\\.")) {
				numbers.add(Long.parseLong(token));
			}
			rest = numeric.group(2);
		}
		else {
			numbers.add(0L);
		}
		String qualifierText = rest.replaceFirst("^[.\\-_]", "").toLowerCase(Locale.ROOT);
		Matcher qualifier = QUALIFIER.matcher(qualifierText);
		if (qualifierText.isEmpty() || !qualifier.find()) {
			return new Parsed(numbers, Qualifier.RELEASE, 0);
		}
		if (qualifier.group(1).equals("snapshot")) {
			// rc 보다 뒤, 정식보다 앞
			return new Parsed(numbers, Qualifier.RELEASE_CANDIDATE, Long.MAX_VALUE);
		}
		long number = qualifier.group(2).isEmpty() ? 0 : Long.parseLong(qualifier.group(2));
		return new Parsed(numbers, Qualifier.of(qualifier.group(1)), number);
	}

	/** 선언 순서가 비교 순서다 */
	private enum Qualifier {

		ALPHA, BETA, MILESTONE, RELEASE_CANDIDATE, RELEASE, SERVICE_PACK;

		static Qualifier of(String name) {
			return switch (name) {
				case "alpha", "a" -> ALPHA;
				case "beta", "b" -> BETA;
				case "milestone", "m" -> MILESTONE;
				case "rc", "cr" -> RELEASE_CANDIDATE;
				case "sp" -> SERVICE_PACK;
				// final, release, ga, jre, android 등 정식 릴리즈를 뜻하거나 변형을 뜻하는 수식어
				default -> RELEASE;
			};
		}

	}

	private record Parsed(List<Long> numbers, Qualifier qualifier, long qualifierNumber) {

		long number(int index) {
			return (index < this.numbers.size()) ? this.numbers.get(index) : 0;
		}

	}

}
