package com.eottabom.migration.result;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 게이트(컴파일, 빌드) 하나의 결과. result.json 에는 {@link #json()} 값으로 쓴다.
 */
public enum Outcome {

	PASSED("ok"), FAILED("fail"), SKIPPED("skip");

	private final String json;

	Outcome(String json) {
		this.json = json;
	}

	public static Outcome of(boolean passed) {
		return passed ? PASSED : FAILED;
	}

	@JsonValue
	public String json() {
		return this.json;
	}

}
