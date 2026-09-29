package com.eottabom.migration.config;

import java.util.Locale;

/** 실행 방식 (--mode). */
public enum Mode {

	/** stage 마다 레시피를 적용하고 게이트를 돈다 */
	STAGED,

	/** 목표까지의 레시피를 한 번에 적용하고 게이트는 마지막에 한 번 */
	ALL,

	/** 소스를 바꾸지 않고 stage 별 patch 만 만든다 */
	PREVIEW;

	public static Mode parse(String option) {
		return valueOf(option.trim().toUpperCase(Locale.ROOT));
	}

	public String option() {
		return name().toLowerCase(Locale.ROOT);
	}

}
