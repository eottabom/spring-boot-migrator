package com.eottabom.migration.pipeline.step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.eottabom.migration.result.Outcome;

/**
 * 게이트 결과.
 *
 * @param compile compile 게이트 결과
 * @param build build 게이트 결과 (테스트 실패는 newFailedTestCount 로 따로 센다)
 * @param newFailedTestCount build 게이트에서 새로 실패한 테스트 수 (다시 돌려도 실패한 것)
 * @param newFailedTasks 원본에서는 실패하지 않던 태스크 중 이 stage 에서 실패한 것
 * @param unknownFailure 빌드는 실패했는데 실패한 태스크를 찾지 못했다 (원인을 모르므로 막는다)
 * @param flakyTests 실패했다가 다시 돌려 통과한 테스트
 * @param testResultFiles 이 build 가 만든 테스트 결과 파일. 결과는 이 파일들만 읽는다
 * @param unreadableResults 끝까지 읽지 못한 테스트 결과 파일 (결과가 실제보다 적을 수 있어 막는다)
 */
public record GateOutcome(Outcome compile, Outcome build, int newFailedTestCount, Set<String> newFailedTasks,
		boolean unknownFailure, Set<String> flakyTests, List<Path> testResultFiles, List<Path> unreadableResults) {

	public static final GateOutcome SKIPPED = compileOnly(Outcome.SKIPPED);

	/** build 게이트는 돌리지 않았다 */
	public static GateOutcome compileOnly(Outcome compile) {
		return new GateOutcome(compile, Outcome.SKIPPED, 0, Set.of(), false, Set.of(), List.of(), List.of());
	}

	public boolean compileFailed() {
		return this.compile == Outcome.FAILED;
	}

	/** 원본에서는 실패하지 않던 태스크가 실패했거나 원인을 모른다 */
	public boolean hasNewBuildFailure() {
		return this.unknownFailure || !this.newFailedTasks.isEmpty();
	}

	public boolean passed() {
		return !compileFailed() && this.newFailedTestCount == 0 && !hasNewBuildFailure()
				&& this.unreadableResults.isEmpty();
	}

	public String describe() {
		if (compileFailed()) {
			return "컴파일 실패";
		}
		List<String> reasons = new ArrayList<>();
		if (this.newFailedTestCount > 0) {
			reasons.add("테스트 " + this.newFailedTestCount + "개 실패");
		}
		if (this.unknownFailure) {
			reasons.add("빌드 실패 (실패한 태스크를 찾지 못함, 로그 확인)");
		}
		else if (!this.newFailedTasks.isEmpty()) {
			reasons.add("빌드 실패 (새로 실패한 태스크 " + String.join(", ", this.newFailedTasks) + ")");
		}
		if (!this.unreadableResults.isEmpty()) {
			reasons.add("테스트 결과 " + this.unreadableResults.size() + "개를 읽지 못함");
		}
		return String.join(", ", reasons);
	}

}
