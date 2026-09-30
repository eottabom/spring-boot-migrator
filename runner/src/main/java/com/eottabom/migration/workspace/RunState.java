package com.eottabom.migration.workspace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.eottabom.migration.stage.StageId;
import com.eottabom.migration.stage.StageTag;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * 한 번의 migrationRun 이 이어서 쓰는 기록 (run-state.json, schema/run-state.schema.json). 끝까지 마치면
 * 지운다.
 *
 * @param project 기록을 남긴 대상 프로젝트 (다른 프로젝트의 기록으로 재개하지 않도록)
 * @param baseRevision 실행을 시작한 HEAD. 재개할 때 같은 브랜치 흐름인지 확인한다
 * @param startRevision 누적 patch 의 기준 (시작 시점 작업 트리를 담은 커밋)
 * @param startBoot 시작할 때의 Boot 버전 (결과 제목의 "시작 → 현재")
 * @param createdFiles 레시피가 만든 파일. patch 와 커밋에 넣는다
 * @param untrackedAtStop 멈출 때 있던 추적 안 된 파일. 재개 때는 그 뒤에 생긴 파일만 사용자가 고치며 만든 파일로 본다
 * @param stopped 게이트에서 멈춘 stage (없으면 null)
 */
public record RunState(int version, String project, String baseRevision, String startRevision,
		@Nullable String startBoot, List<String> createdFiles, List<String> untrackedAtStop, Baseline baseline,
		@Nullable Stopped stopped) {

	public static final int VERSION = 1;

	public static RunState start(String project, String baseRevision, String startRevision,
			@Nullable String startBoot) {
		return new RunState(VERSION, project, baseRevision, startRevision, startBoot, List.of(), List.of(),
				Baseline.NONE, null);
	}

	public RunState withCreatedFiles(Set<String> files) {
		Set<String> all = new LinkedHashSet<>(this.createdFiles);
		all.addAll(files);
		return new RunState(this.version, this.project, this.baseRevision, this.startRevision, this.startBoot,
				new ArrayList<>(all), this.untrackedAtStop, this.baseline, this.stopped);
	}

	public RunState withBaseline(Baseline baseline) {
		return new RunState(this.version, this.project, this.baseRevision, this.startRevision, this.startBoot,
				this.createdFiles, this.untrackedAtStop, baseline, this.stopped);
	}

	public RunState stoppedAt(Stopped stopped, Set<String> untracked) {
		return new RunState(this.version, this.project, this.baseRevision, this.startRevision, this.startBoot,
				this.createdFiles, new ArrayList<>(untracked), this.baseline, stopped);
	}

	public RunState resumed() {
		return new RunState(this.version, this.project, this.baseRevision, this.startRevision, this.startBoot,
				this.createdFiles, this.untrackedAtStop, this.baseline, null);
	}

	/**
	 * 원본 빌드에서도 실패하던 태스크와 테스트. stage 에서는 여기 없는 실패만 막는다.
	 *
	 * @param failedTests 클래스#메서드
	 */
	public record Baseline(Set<String> failedTasks, Set<String> failedTests) {

		/** 원본 빌드를 돌리지 않았거나 모두 통과했다 */
		public static final Baseline NONE = new Baseline(Set.of(), Set.of());

	}

	/**
	 * 게이트에서 멈춘 stage. 같은 명령을 다시 실행하면 여기서 이어서 한다.
	 *
	 * @param covers stage 가 다루는 stage (--mode=all 이면 여러 개)
	 * @param previousTag 마지막으로 통과한 stage 의 태그 (없으면 null)
	 * @param treeBefore stage 전 작업 트리. 재개해서 통과하면 이 tree 부터 stage diff 를 다시 만든다
	 */
	public record Stopped(StageId stage, StageTag tag, List<StageId> covers, @Nullable StageTag previousTag,
			Reason reason, @Nullable String treeBefore) {
	}

	/** 멈춘 이유 */
	public enum Reason {

		@JsonProperty("compile")
		COMPILE,

		@JsonProperty("build")
		BUILD

	}

}
