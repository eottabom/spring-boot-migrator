package com.eottabom.migration.guide;

import org.jspecify.annotations.Nullable;

/**
 * stage 에 걸린 체크리스트 항목.
 *
 * @param trigger 라이브러리 조건으로 걸린 경우 "group:artifact 이전 → 이후", stage 조건이면 null
 */
public record ChecklistMatch(ChecklistItem item, @Nullable String trigger) {
}
