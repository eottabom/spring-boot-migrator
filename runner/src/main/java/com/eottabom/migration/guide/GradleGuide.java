package com.eottabom.migration.guide;

import java.util.List;

/** guides/gradle/&lt;버전&gt;.yml. 파일이 있는 버전이 Gradle stage 가 된다. */
public record GradleGuide(String version, String source, List<ChecklistItem> checklist, List<FailureHint> failureHints,
		List<Deprecation> deprecations) implements StageGuide {
}
