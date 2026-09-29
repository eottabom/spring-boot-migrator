package com.eottabom.migration.config;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * 목표 Java (--java).
 *
 * @param version kind 가 VERSION 일 때만 값이 있다
 */
public record JavaTarget(Kind kind, @Nullable Integer version) {

	/** 기본값. 목표 Boot 가 지원하는 가장 높은 LTS 까지 같이 올린다 */
	public static final JavaTarget LATEST = new JavaTarget(Kind.LATEST, null);

	public static JavaTarget parse(String option) {
		String value = option.trim().toLowerCase(Locale.ROOT);
		return switch (value) {
			case "latest" -> LATEST;
			case "keep" -> new JavaTarget(Kind.KEEP, null);
			case "none" -> new JavaTarget(Kind.NONE, null);
			default -> {
				if (!value.matches("\\d+")) {
					throw new IllegalArgumentException("--java 는 latest | keep | none | 17 | 21 | 25");
				}
				yield new JavaTarget(Kind.VERSION, Integer.parseInt(value));
			}
		};
	}

	@Override
	public String toString() {
		return (this.kind == Kind.VERSION) ? String.valueOf(this.version) : this.kind.name().toLowerCase(Locale.ROOT);
	}

	public enum Kind {

		/** 목표 Boot 가 지원하는 가장 높은 LTS (기본값) */
		LATEST,

		/** 목표 Boot 가 지원하면 지금 Java 를 유지 */
		KEEP,

		/** 올리지 않는다 */
		NONE,

		/** 지정한 버전 */
		VERSION

	}

}
