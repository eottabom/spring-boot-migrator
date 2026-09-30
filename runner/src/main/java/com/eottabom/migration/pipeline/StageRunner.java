package com.eottabom.migration.pipeline;

import java.util.List;

import com.eottabom.migration.MigrationException;
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
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.result.Outcome;
import com.eottabom.migration.result.StageResult;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.version.ResolvedVersions;
import com.eottabom.migration.workspace.RunState.Reason;
import com.eottabom.migration.workspace.RunState.Stopped;
import com.eottabom.migration.workspace.StageFiles;
import org.jspecify.annotations.Nullable;

/**
 * stage 하나의 step. Rewrite, Gate(compile), Deprecation, Gate(build), Assess, Record,
 * Commit 을 차례로 부른다. 재개할 때는 Rewrite 만 빼고 같은 순서로 다시 돈다. 게이트를 통과하지 못하면 재개 기록을 남기고 멈춘다.
 */
final class StageRunner {

	private final RunSession session;

	StageRunner(RunSession session) {
		this.session = session;
	}

	void run(Stage stage, StageTag tag) {
		// 되돌리고 같은 태그로 다시 시도하면 지난 시도의 결과가 남아 있다
		this.session.ws().clearStage(tag);
		StageFiles files = this.session.ws().stage(tag);
		RewriteOutcome rewrite = this.session.rewriteStep()
			.run(stage, tag, files, this.session.projectRecipes(), this.session.state().createdFiles(),
					this.session.ws().tempIndex());
		this.session.addCreatedFiles(rewrite.createdFiles());
		ProjectState rewritten = this.session.inspector().inspect(this.session.projectDir());
		this.session.console()
			.line("   Boot {} / Gradle {}", rewritten.bootVersion(),
					RunnerConsole.orUnknown(rewritten.gradleVersion()));

		// 이 stage 의 게이트부터 바뀐 Java 버전으로 돈다
		this.session.refreshJdk(rewritten);
		StageRun run = new StageRun(stage, tag, files, rewrite.treeBefore());
		complete(run, verify(run), "(OpenRewrite " + stage.recipeNames() + ")");
	}

	/**
	 * 게이트에서 멈췄던 stage 를 Rewrite 없이 같은 순서로 다시 확인한다.
	 * @return 컴파일로 멈췄는데 아직 컴파일되지 않으면 false. 이때는 아무것도 기록하지 않고, 호출하는 쪽이 되돌릴지 정한다
	 */
	boolean resume(Stopped stopped) {
		StageRun run = new StageRun(new Stage(stopped.stage(), List.of(), stopped.covers()), stopped.tag(),
				this.session.ws().stage(stopped.tag()), stopped.treeBefore());
		Verified verified = verify(run);
		if (verified.gate().compileFailed() && stopped.reason() == Reason.COMPILE) {
			return false;
		}
		this.session.history().resuming(stopped.stage());
		complete(run, verified, "(재개, 수정 포함)");
		return true;
	}

	/** compile 게이트, deprecated API 대체, build 게이트. 처음 돌 때와 재개할 때 같은 순서로 돈다 */
	private Verified verify(StageRun run) {
		if (!this.session.config().gate().level().compiles()) {
			return new Verified(GateOutcome.SKIPPED, List.of());
		}
		GateStep gateStep = this.session.gateStep();
		List<String> deprecationFixes = List.of();
		Outcome compile = gateStep.compile(run.stage().name(), run.files());
		if (compile == Outcome.PASSED) {
			DeprecationStep.Fixed fixed = new DeprecationStep(this.session.guides(), this.session.recipeRun(),
					this.session.console())
				.fix(run.stage(), run.tag(), run.files(), this.session.projectDir());
			if (fixed.applied()) {
				deprecationFixes = fixed.recipes();
				this.session.addCreatedFiles(fixed.createdFiles());
				compile = gateStep.compile(run.stage().name(), run.files());
			}
		}
		boolean builds = compile == Outcome.PASSED && this.session.config().gate().level().builds();
		GateOutcome gate = builds ? gateStep.build(run.stage().name(), run.files()) : GateOutcome.compileOnly(compile);
		return new Verified(gate, deprecationFixes);
	}

	/** Assess 와 Record 뒤에 게이트 결과로 멈추거나 커밋하고 다음 stage 로 간다 */
	private void complete(StageRun run, Verified verified, String commitDetail) {
		// stage 후의 버전은 한 번 읽어 결과와 다음 stage 의 비교에 같이 쓴다
		ResolvedVersions versionsAfter = ResolvedVersions.read(run.files().versions());
		record(run, verified, versionsAfter);
		if (!verified.gate().passed()) {
			throw stopped(run, verified.gate());
		}
		if (this.session.config().commit()) {
			new CommitStep(this.session.console()).commit(this.session.git(), this.session.state().createdFiles(),
					run.files(), run.tag(), commitDetail);
		}
		this.session.passed(run.tag(), versionsAfter);
	}

	/** result.md, result.json, patch, result.html, history.md 의 stage 한 줄 */
	private void record(StageRun run, Verified verified, ResolvedVersions versionsAfter) {
		StageResult result = new AssessStep(this.session.guides(), this.session.projectDir(), this.session.ws().start(),
				this.session.projectRecipes())
			.assess(run.stage(), run.files(), this.session.previousVersions(), versionsAfter, verified.gate(),
					verified.deprecationFixes(), this.session.state().baseline().failedTests());
		RecordStep record = new RecordStep(this.session.console());
		String row = record.write(result, run.files()).historyRow(run.tag());
		if (this.session.isGit()) {
			new StagePatches(this.session.console(), this.session.git()).write(run.files(),
					this.session.state().createdFiles(), this.session.ws().tempIndex(), run.treeBefore(),
					this.session.state().startRevision());
		}
		record.html(this.session.ws(), this.session.projectName(), this.session.state().startBoot(),
				this.session.currentBoot());
		this.session.history().row(row);
	}

	/** 깨진 상태로 다음 stage 로 가거나 커밋하지 않는다. 멈춘 자리를 남기고, 같은 명령을 다시 실행하면 이 stage 부터 이어서 한다 */
	private MigrationException stopped(StageRun run, GateOutcome gate) {
		String stageName = run.stage().name();
		this.session.history().stopped(run.tag(), gate.describe());
		Reason reason = gate.compileFailed() ? Reason.COMPILE : Reason.BUILD;
		this.session.stopAt(new Stopped(run.stage().id(), run.tag(), run.stage().covers(), this.session.lastTag(),
				reason, run.treeBefore()));
		if (gate.compileFailed()) {
			return new MigrationException("[" + stageName + "] 컴파일 실패. 에러는 " + run.files().compileLog() + "\n"
					+ "   같은 명령을 다시 실행하면, 에러를 고쳤을 땐 이 stage 의 테스트/빌드 검증을 이어서 하고\n"
					+ (this.session.isGit()
							? "   그대로면 " + stageName + " stage 전 상태로 되돌려 " + stageName + " stage 를 다시 시도해요."
							: "   그대로면 멈춰요 (git 저장소가 아니라 stage 전 상태로 되돌릴 수 없어요)."));
		}
		return new MigrationException("[" + stageName + "] " + gate.describe() + ". 결과는 "
				+ this.session.ws().resultHtml().toUri() + "\n" + "   고치고 같은 명령을 다시 실행하면 이 stage 검증부터 다시 하고, 통과하면 "
				+ (this.session.config().commit() ? "커밋하고 " : "")
				+ "다음 stage 로 넘어가요. 테스트 결과와 상관없이 진행하려면 --gate=compile");
	}

	/**
	 * 한 stage 를 도는 동안 같이 다니는 값.
	 *
	 * @param treeBefore stage 전 작업 트리 (git 저장소가 아니면 null)
	 */
	private record StageRun(Stage stage, StageTag tag, StageFiles files, @Nullable String treeBefore) {
	}

	/**
	 * @param deprecationFixes deprecated API 를 바꾼 대체 레시피
	 */
	private record Verified(GateOutcome gate, List<String> deprecationFixes) {
	}

}
