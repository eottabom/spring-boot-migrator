package com.eottabom.migration.guide;

import java.util.List;

/** guides/common.yml. 모든 stage 에 쓰는 실패 힌트와 deprecated API 대체 레시피. */
public record CommonGuide(List<FailureHint> failureHints, List<Deprecation> deprecations) {

	static final CommonGuide EMPTY = new CommonGuide(List.of(), List.of());

}
