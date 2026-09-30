package com.eottabom.migration.plan;

import java.util.List;

import com.eottabom.migration.stage.StageId;
import com.eottabom.migration.stage.StageTag;

/**
 * 마이그레이션 stage 하나. stage 마다 rewriteRun 과 게이트를 한 번씩 돈다.
 *
 * @param recipes 차례로 실행할 레시피 (stage.Boot_3_4 등)
 * @param covers 이 stage 가 다루는 stage. 보통은 자기 하나, --mode=all 이면 목표까지의 모든 stage
 */
public record Stage(StageId id, List<String> recipes, List<StageId> covers) {

	/** 레시피 하나로 도는 보통 stage */
	public Stage(StageId id, String recipe) {
		this(id, List.of(recipe), List.of(id));
	}

	public String name() {
		return this.id.name();
	}

	public StageTag tag(int order) {
		return new StageTag(order, this.id);
	}

	/** 콘솔과 커밋 메시지에 쓰는 레시피 목록 */
	public String recipeNames() {
		return String.join(", ", this.recipes);
	}

}
