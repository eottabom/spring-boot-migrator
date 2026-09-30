package com.eottabom.migration.pipeline;

import java.util.List;

import com.eottabom.migration.MigrationException;
import com.eottabom.migration.config.GateLevel;
import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.io.TextFiles;
import com.eottabom.migration.pipeline.step.TestRun;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.result.TestResults;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.RunFiles;

/**
 * migrationVerify. 현재 소스의 컴파일(+제거 예정 API 경고)과 build(전체 테스트 + 패키징). 소스는 바꾸지 않는다.
 */
record VerifyCommand(RunnerConsole console) {

	void verify(ProjectState project, ProjectGradle gradle, GateLevel gate) {
		if (!gate.compiles()) {
			throw new MigrationException("migrationVerify 의 --gate 는 compile 또는 build");
		}
		RunFiles files = new RunFiles(MigrationWorkspace.in(project.dir()).dir().resolve("verify"));
		this.console.project(project, gradle.javaHome(), RunnerOutputs.workingTree(project.dir()).describe());
		this.console.heading("[verify] compile (+deprecation/removal 경고 수집)");
		if (!gradle.verify(files.compileLog(), List.of("clean", "compileJava", "compileTestJava"))) {
			throw new MigrationException("컴파일 실패 → " + files.compileLog());
		}
		this.console.line("   [removal] 경고 {}건, [deprecation] 경고 {}건 → {}",
				TextFiles.countMatches(files.compileLog(), "\\[removal\\]"),
				TextFiles.countMatches(files.compileLog(), "\\[deprecation\\]"), files.compileLog());
		if (gate.builds()) {
			build(project, gradle, files);
		}
	}

	private void build(ProjectState project, ProjectGradle gradle, RunFiles files) {
		this.console.heading("[verify] build (전체 테스트 + 패키징)");
		TestRun.Result run = new TestRun(gradle, project.dir()).run(files.buildLog(), files.testDirs(),
				List.of("build"), true);
		TestResults.Results tests = run.tests();
		this.console.line("   테스트 {}개, 실패 {}개", tests.total(), tests.failed());
		if (!run.built()) {
			throw new MigrationException("테스트 외 태스크(패키징, 플러그인, 검사)에서 빌드 실패 → " + files.buildLog());
		}
		if (!tests.complete()) {
			throw new MigrationException("테스트 결과 " + tests.unreadable().size() + "개를 읽지 못했어요 " + tests.unreadable());
		}
		if (tests.failed() > 0) {
			throw new MigrationException(
					"테스트 실패 " + tests.failed() + "개 → " + project.dir().resolve("build/reports/tests"));
		}
	}

}
