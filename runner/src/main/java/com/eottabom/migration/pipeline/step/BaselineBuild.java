package com.eottabom.migration.pipeline.step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.eottabom.migration.MigrationException;
import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.gradle.FailedTasks;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.result.TestResults;
import com.eottabom.migration.workspace.RunFiles;
import com.eottabom.migration.workspace.RunState.Baseline;

/**
 * 원본 빌드를 한 번 돌려 원래부터 실패하던 태스크와 테스트를 모은다. stage 에서는 여기 없는 실패만 막는다. 원본 컴파일이 깨지면 레시피를 돌릴 수
 * 없어 멈춘다.
 */
public record BaselineBuild(ProjectGradle gradle, RunnerConsole console, Path projectDir) {

	/**
	 * @param skipTests 테스트를 건너뛴다. 원본 테스트 실패를 모으지 않아 stage 의 테스트 실패는 모두 새 실패가 된다
	 */
	public Result run(RunFiles start, boolean skipTests) {
		Path log = start.baselineBuildLog();
		List<String> args = new ArrayList<>(
				List.of("clean", "build", "--continue", "-PmigrationFailedTasksOut=" + start.baselineFailedTasks()));
		if (skipTests) {
			args.addAll(List.of("-x", "test"));
			this.console.line("   원본 빌드에서 테스트를 건너뛰어요. stage 의 테스트 실패는 모두 새 실패로 봐요");
		}
		TestRun.Result run = new TestRun(this.gradle, this.projectDir).run(log, start.baselineTestDirs(), args, true);
		Set<String> failedTasks = run.built() ? Set.of() : FailedTasks.read(start.baselineFailedTasks(), log);
		if (FailedTasks.anyCompileTask(failedTasks)) {
			throw new MigrationException("현재 소스가 컴파일되지 않아요. 컴파일 에러를 고치고 다시 실행해 주세요 → " + log);
		}
		TestResults.Results tests = run.tests();
		if (!tests.complete()) {
			// 읽지 못한 결과의 실패는 원본 실패로 기록되지 않아 stage 에서 새 실패로 잡힌다 (보수적으로 막는 쪽)
			this.console.error("원본 테스트 결과 " + tests.unreadable().size() + "개를 읽지 못했어요 " + tests.unreadable());
		}
		List<String> notes = new ArrayList<>();
		if (!failedTasks.isEmpty()) {
			this.console.line("   원본 빌드에서도 실패하는 태스크 (기존 문제, 컴파일은 통과) {} → {}", failedTasks, log);
			notes.add("원본 빌드에서도 실패하는 태스크 (기존 문제, 마이그레이션 무관) " + failedTasks + ", start/baseline-build.log");
		}
		if (tests.failed() > 0) {
			this.console.line("   원본에서도 실패하는 테스트 {}개 (stage 를 막지 않아요)", tests.failed());
			notes.add("원본에서도 실패하는 테스트 " + tests.failed() + "개 (기존 문제라 stage 를 막지 않아요)");
		}
		return new Result(new Baseline(Set.copyOf(failedTasks), Set.copyOf(tests.failedTests())), notes);
	}

	/**
	 * @param notes history.md 에 남길 줄
	 */
	public record Result(Baseline baseline, List<String> notes) {
	}

}
