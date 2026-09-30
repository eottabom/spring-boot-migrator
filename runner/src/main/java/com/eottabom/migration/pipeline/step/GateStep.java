package com.eottabom.migration.pipeline.step;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.gradle.FailedTasks;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.gradle.VerifyInitScript;
import com.eottabom.migration.result.Outcome;
import com.eottabom.migration.result.TestResults;
import com.eottabom.migration.workspace.RunState.Baseline;
import com.eottabom.migration.workspace.StageFiles;

/**
 * stage 게이트. compile 과 build 를 돌려 원본 빌드에 없던 실패만 막는다.
 *
 * @param projectDir 대상 프로젝트 (테스트 결과를 찾는 곳)
 * @param testRetries 새로 실패한 테스트를 다시 돌리는 횟수. 다시 돌려 통과하면 불안정한 테스트로 보고 stage 를 막지 않는다
 * @param baseline 원본에서도 실패하던 태스크와 테스트
 */
public record GateStep(ProjectGradle gradle, VerifyInitScript verifyInit, RunnerConsole console, Path projectDir,
		int testRetries, Baseline baseline) {

	/**
	 * compile (+deprecation/removal 경고). 컴파일이 깨져도 stage 후 의존성 버전 목록은 남긴다.
	 */
	public Outcome compile(String stageName, StageFiles files) {
		this.console.step("[" + stageName + "] compile (+deprecation/removal 경고 수집)");
		// clean: rewriteRun 이 컴파일하며 src/main/generated 에 만든 Q-class 와 APT 가 다시 충돌하지 않도록
		boolean compiled = this.gradle.run(files.compileLog(), this.verifyInit.args("clean", "compileJava",
				"compileTestJava", "migrationResolvedVersions", "-PmigrationVersionsOut=" + files.versions()));
		if (!Files.exists(files.versions())) {
			this.gradle.runQuietly(
					this.verifyInit.args("migrationResolvedVersions", "-PmigrationVersionsOut=" + files.versions()));
		}
		return Outcome.of(compiled);
	}

	/**
	 * 전체 테스트와 패키징. 테스트는 ignoreFailures 로 끝까지 돌려 결과 XML 로 세고, --continue 로 실패한 태스크를 모두
	 * 모은다. 원본에서도 실패하던 태스크만 실패했으면 막지 않고, 실패한 태스크를 찾지 못하거나 결과 XML 을 읽지 못하면 원인을 모르므로 막는다.
	 * @param clean 재개할 때는 사용자가 고친 뒤라 clean 부터 한다
	 */
	public GateOutcome build(String stageName, StageFiles files, boolean clean) {
		this.console.step("[" + stageName + "] build (전체 테스트 + 패키징, properties-migrator 경고 수집)");
		List<String> args = new ArrayList<>(
				clean ? List.of("clean", "build", "--continue") : List.of("build", "--continue"));
		args.add("-PmigrationFailedTasksOut=" + files.failedTasks());
		TestRun.Result run = new TestRun(this.gradle, this.verifyInit, this.projectDir).run(files.buildLog(),
				files.testDirs(), args, true);
		TestResults.Results tests = run.tests();
		Set<String> newFailures = new TreeSet<>(tests.failedTests());
		newFailures.removeAll(this.baseline.failedTests());
		int existing = tests.failed() - newFailures.size();
		this.console.line("   테스트 {}개, 실패 {}개{}", tests.total(), newFailures.size(),
				(existing > 0) ? " (원본에서도 실패하던 " + existing + "개 제외)" : "");
		FlakyTestRetry.Retried retried = new FlakyTestRetry(this.gradle, this.verifyInit, this.console, this.projectDir,
				this.testRetries)
			.retry(files, newFailures, run.files());
		if (!tests.complete()) {
			this.console.error("테스트 결과 " + tests.unreadable().size() + "개를 읽지 못했어요 (또는 테스트를 돌린 태스크의 결과가 없어요)");
		}
		Set<String> newFailedTasks = new TreeSet<>();
		boolean unknownFailure = false;
		if (!run.built()) {
			Set<String> failedTasks = FailedTasks.read(files.failedTasks(), files.buildLog());
			unknownFailure = failedTasks.isEmpty();
			failedTasks.removeAll(this.baseline.failedTasks());
			newFailedTasks.addAll(failedTasks);
			if (newFailedTasks.isEmpty() && !unknownFailure) {
				this.console.line("   빌드 실패: 원본에서도 실패하던 태스크만 실패했어요");
			}
		}
		return new GateOutcome(Outcome.PASSED, Outcome.of(run.built()), retried.failing().size(), newFailedTasks,
				unknownFailure, retried.flaky(), run.files(), tests.unreadable());
	}

}
