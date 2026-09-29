package com.eottabom.migration.guide;

import java.util.List;

/**
 * guides/java/&lt;버전&gt;.yml. LTS 만 둔다.
 *
 * @param gradle 이 JDK 위에서 Gradle 을 띄우려면 필요한 최소 버전
 */
public record JavaGuide(int version, String source, String gradle, List<ChecklistItem> checklist,
		List<FailureHint> failureHints, List<Deprecation> deprecations) implements StageGuide {
}
