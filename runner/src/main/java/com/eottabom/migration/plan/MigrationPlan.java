package com.eottabom.migration.plan;

import java.util.List;

import com.eottabom.migration.guide.BootRequirements;
import org.jspecify.annotations.Nullable;

/**
 * @param targetRequirements 목표 Boot 의 시스템 요구 사항
 * @param targetJava 올리지 않으면 null
 * @param stages 현재 버전의 다음 stage 부터 목표까지. 비어 있으면 할 일이 없다
 * @param notes Java, Gradle 판단 근거와 경고
 */
public record MigrationPlan(String targetBoot, BootRequirements targetRequirements, @Nullable Integer targetJava,
		List<Stage> stages, List<String> notes) {

	public boolean isEmpty() {
		return this.stages.isEmpty();
	}

	public String stageNames() {
		return String.join(" ", this.stages.stream().map(Stage::name).toList());
	}

}
