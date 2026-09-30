package com.eottabom.migration.pipeline;

import java.util.List;
import java.util.Set;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.pipeline.step.AssessStep;
import com.eottabom.migration.pipeline.step.CommitStep;
import com.eottabom.migration.pipeline.step.DeprecationStep;
import com.eottabom.migration.pipeline.step.GateOutcome;
import com.eottabom.migration.pipeline.step.GateStep;
import com.eottabom.migration.pipeline.step.RecordStep;
import com.eottabom.migration.pipeline.step.RewriteStep.RewriteOutcome;
import com.eottabom.migration.pipeline.step.StagePatches;
import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.result.Outcome;
import com.eottabom.migration.result.StageResult;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.workspace.RunState.Reason;
import com.eottabom.migration.workspace.RunState.Stopped;
import com.eottabom.migration.workspace.StageFiles;
import org.gradle.api.GradleException;
import org.jspecify.annotations.Nullable;

/**
 * stage 하나의 step. Rewrite, Gate(compile), Deprecation, Gate(build), Assess, Record,
 * Commit 을 차례로 부른다. 재개할 때는 Rewrite 없이 게이트부터 다시 확인한다. 게이트를 통과하지 못하면 재개 기록을 남기고 멈춘다.
 */
record StageRunner(RunSession session) {

	void run(Stage stage, StageTag tag) {
		// 되돌리고 같은 태그로 다시 시도하면 지난 시도의 결과가 남아 있다
		session().ws().clearStage(tag);
		StageFiles files = session().ws().stage(tag);
		RewriteOutcome rewrite = session().rewriteStep()
			.run(stage, tag, files, session().projectRecipes(), session().state().createdFiles(),
					session().ws().tempIndex());
		session().state(session().state().withCreatedFiles(rewrite.createdFiles()));
		session().console()
			.line("   Boot {} / Gradle {}", session().components().inspector().bootVersion(session().projectDir()),
					RunnerConsole.orUnknown(session().components().inspector().gradleVersion(session().projectDir())));

		// 이 stage 의 게이트부터 바뀐 Java 버전으로 돈다
		session().refreshJdk();
		List<String> deprecationFixes = List.of();
		GateOutcome gate = GateOutcome.SKIPPED;
		if (session().config().gate().level().compiles()) {
			GateStep gateStep = session().gateStep();
			Outcome compile = gateStep.compile(stage.name(), files);
			if (compile == Outcome.PASSED) {
				DeprecationStep.Fixed fixed = new DeprecationStep(session().components().guides(),
						session().rewriteStep(), session().console())
					.fix(stage, tag, files, session().projectDir());
				if (fixed.applied()) {
					deprecationFixes = fixed.recipes();
					session().state(session().state().withCreatedFiles(fixed.createdFiles()));
					compile = gateStep.compile(stage.name(), files);
				}
			}
			gate = (compile == Outcome.PASSED && session().config().gate().level().builds())
					? gateStep.build(stage.name(), files, false) : GateOutcome.compileOnly(compile);
		}
		complete(stage, tag, files, gate, deprecationFixes, rewrite.treeBefore(),
				"(OpenRewrite " + stage.recipeNames() + ")");
	}

	/**
	 * 게이트에서 멈췄던 stage 를 다시 확인한다. 컴파일은 호출하는 쪽이 이미 확인했다.
	 */
	void resume(Stopped stopped) {
		StageFiles files = session().ws().stage(stopped.tag());
		GateOutcome gate = session().config().gate().level().builds() ? session().gateStep().build("재개", files, true)
				: GateOutcome.compileOnly(Outcome.PASSED);
		Stage stage = new Stage(stopped.stage(), List.of(), stopped.covers());
		complete(stage, stopped.tag(), files, gate, List.of(), stopped.treeBefore(), "(재개, 수정 포함)");
	}

	/** Assess 와 Record 뒤에 게이트 결과로 멈추거나 커밋하고 다음 stage 로 간다 */
	private void complete(Stage stage, StageTag tag, StageFiles files, GateOutcome gate, List<String> deprecationFixes,
			@Nullable String treeBefore, String commitDetail) {
		record(stage, tag, files, gate, deprecationFixes, treeBefore);
		if (!gate.passed()) {
			stop(stage, tag, files, gate, treeBefore);
		}
		if (session().config().commit()) {
			new CommitStep(session().console()).commit(session().git(), session().state().createdFiles(), files, tag,
					commitDetail);
		}
		session().passed(tag);
	}

	/** result.md, result.json, patch, result.html, history.md 의 stage 한 줄 */
	private void record(Stage stage, StageTag tag, StageFiles files, GateOutcome gate, List<String> deprecationFixes,
			@Nullable String treeBefore) {
		StageResult result = new AssessStep(session().components().guides()).assess(stage.id(), stage.covers(),
				session().projectDir(), files, session().ws().start(), session().previousVersions(), gate,
				deprecationFixes, session().projectRecipes(), session().state().baseline().failedTests());
		RecordStep record = new RecordStep(session().console());
		String row = record.write(result, files).historyRow(tag);
		if (session().isGit()) {
			new StagePatches(session().console(), session().git()).write(files, session().state().createdFiles(),
					session().ws().tempIndex(), treeBefore, session().state().startRevision());
		}
		record.html(session().ws(), session().projectName(), session().state().startBoot(),
				session().components().inspector().bootVersion(session().projectDir()));
		session().history().row(row);
	}

	/** 깨진 상태로 다음 stage 로 가거나 커밋하지 않는다. 같은 명령을 다시 실행하면 이 stage 부터 이어서 한다 */
	private void stop(Stage stage, StageTag tag, StageFiles files, GateOutcome gate, @Nullable String treeBefore) {
		session().history().stopped(tag, gate.describe());
		Reason reason = gate.compileFailed() ? Reason.COMPILE : Reason.BUILD;
		session().state(session().state()
			.stoppedAt(new Stopped(stage.id(), tag, stage.covers(), session().lastTag(), reason, treeBefore),
					session().isGit() ? session().git().untracked() : Set.of()));
		if (gate.compileFailed()) {
			throw new GradleException("[" + stage.name() + "] 컴파일 실패. 에러는 " + files.compileLog() + "\n"
					+ "   같은 명령을 다시 실행하면, 에러를 고쳤을 땐 이 stage 의 테스트/빌드 검증을 이어서 하고\n"
					+ (session().isGit()
							? "   그대로면 " + stage.name() + " stage 전 상태로 되돌려 " + stage.name() + " stage 를 다시 시도해요."
							: "   그대로면 멈춰요 (git 저장소가 아니라 stage 전 상태로 되돌릴 수 없어요)."));
		}
		throw new GradleException("[" + stage.name() + "] " + gate.describe() + ". 결과는 "
				+ session().ws().resultHtml().toUri() + "\n" + "   고치고 같은 명령을 다시 실행하면 이 stage 검증부터 다시 하고, 통과하면 "
				+ (session().config().commit() ? "커밋하고 " : "") + "다음 stage 로 넘어가요. 테스트 결과와 상관없이 진행하려면 --gate=compile");
	}

}
