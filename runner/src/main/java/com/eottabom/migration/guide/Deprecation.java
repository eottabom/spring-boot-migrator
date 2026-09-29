package com.eottabom.migration.guide;

import java.util.regex.Pattern;

/**
 * javac 의 [removal] / [deprecation] 경고 메시지에 pattern 이 맞으면 같은 stage 안에서 recipe 로 바꾼다.
 */
public record Deprecation(Pattern pattern, String recipe) {

	public boolean matches(String warning) {
		return this.pattern.matcher(warning).find();
	}

}
