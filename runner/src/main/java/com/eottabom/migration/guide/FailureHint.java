package com.eottabom.migration.guide;

import java.util.regex.Pattern;

/**
 * 테스트 실패의 가장 안쪽 예외(이름: 메시지)에 pattern 이 맞으면 붙는 한 줄.
 */
public record FailureHint(Pattern pattern, String text) {

	public boolean matches(String failure) {
		return this.pattern.matcher(failure).find();
	}

}
