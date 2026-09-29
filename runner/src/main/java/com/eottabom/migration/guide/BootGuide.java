package com.eottabom.migration.guide;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * guides/boot/&lt;버전&gt;.yml.
 *
 * @param raises 이 stage 의 upstream 레시피가 함께 올리는 Gradle, Java (없으면 null)
 */
public record BootGuide(String version, String source, BootRequirements requirements, @Nullable Raises raises,
		List<ChecklistItem> checklist, List<FailureHint> failureHints,
		List<Deprecation> deprecations) implements StageGuide {

	/**
	 * @param gradle Gradle wrapper 를 올리는 최소 버전 (올리지 않으면 null)
	 * @param java Java 를 올리는 최소 버전 (올리지 않으면 null)
	 */
	public record Raises(@Nullable String gradle, @Nullable Integer java) {
	}

}
