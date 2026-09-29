package com.eottabom.migration.guide;

import java.util.List;

/** stage 하나의 가이드 (Boot, Java, Gradle). */
public sealed interface StageGuide permits BootGuide, JavaGuide, GradleGuide {

	/** 공식 마이그레이션 가이드나 릴리스 노트 */
	String source();

	List<ChecklistItem> checklist();

	List<FailureHint> failureHints();

	List<Deprecation> deprecations();

}
