package com.eottabom.migration.result;

import java.util.Locale;

import com.eottabom.migration.guide.ChecklistItem;
import com.eottabom.migration.guide.ChecklistMatch;
import org.jspecify.annotations.Nullable;

/**
 * 결과에 넣는 체크리스트 항목. 러너가 guides/ 에서 고르고 결과는 그리기만 한다.
 *
 * @param fix auto | assisted | manual
 * @param recipe 고치는 레시피 (없으면 null)
 * @param detect 위치를 찾는 레시피 (없으면 null)
 * @param trigger 라이브러리 버전 조건으로 걸린 경우 그 변화, stage 조건이면 null
 */
public record ReportedChecklistItem(String id, String fix, String title, @Nullable String detail,
		@Nullable String source, @Nullable String recipe, @Nullable String detect, @Nullable String trigger) {

	public static ReportedChecklistItem of(ChecklistMatch match) {
		ChecklistItem item = match.item();
		return new ReportedChecklistItem(item.id(), item.fix().name().toLowerCase(Locale.ROOT), item.title(),
				item.detail(), item.source(), item.recipe(), item.detect(), match.trigger());
	}

}
