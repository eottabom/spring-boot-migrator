package com.eottabom.migration.config;

import java.util.Locale;

/**
 * stage 마다 확인하는 범위 (--gate).
 */
public enum Gate {

	/** 컴파일만 */
	COMPILE,

	/** 컴파일 + 전체 테스트 + 패키징, asciidoctor, checkstyle 등 */
	BUILD,

	/** 확인하지 않는다 */
	NONE;

	public static Gate parse(String option) {
		return switch (option.trim().toLowerCase(Locale.ROOT)) {
			case "compile" -> COMPILE;
			case "build" -> BUILD;
			case "none" -> NONE;
			default -> throw new IllegalArgumentException("--gate 는 compile | build | none");
		};
	}

	public boolean compiles() {
		return this != NONE;
	}

	public boolean builds() {
		return this == BUILD;
	}

	/** 옵션에 쓰는 이름 (compile, build, none) */
	public String option() {
		return name().toLowerCase(Locale.ROOT);
	}

}
