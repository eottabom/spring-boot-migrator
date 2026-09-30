package com.eottabom.migration.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import com.eottabom.migration.MigrationException;
import com.eottabom.migration.workspace.RunState;
import com.eottabom.migration.workspace.RunState.Reason;
import com.eottabom.migration.workspace.RunState.Stopped;
import org.jspecify.annotations.Nullable;

/**
 * 게이트에서 멈춘 기록(run-state.json)으로 이어서 한다. 멈춘 stage 를 Rewrite 만 빼고 같은 순서(compile, deprecated
 * API 대체, build)로 다시 확인해 통과할 때만 다음 stage 로 간다. 컴파일로 멈췄는데 아직 깨져 있으면 그 stage 전 상태로 되돌려 다시
 * 시도한다.
 */
final class Resumption {

	private final RunSession session;

	private final StageRunner stages;

	Resumption(RunSession session, StageRunner stages) {
		this.session = session;
		this.stages = stages;
	}

	Resumed resume() {
		Optional<RunState> saved = this.session.savedState();
		Stopped stopped = saved.map(RunState::stopped).orElse(null);
		if (stopped == null) {
			return Resumed.NONE;
		}
		RunState stoppedState = saved.get();
		this.session.restore(stoppedState);
		if (this.session.isGit()) {
			checkResumable(stopped);
		}
		this.session.continueAfter(stopped.previousTag());
		this.session.console()
			.heading("[재개] 지난 실행이 " + stopped.stage() + " stage 에서 "
					+ ((stopped.reason() == Reason.COMPILE) ? "컴파일이" : "테스트나 빌드가") + " 실패해 멈췄어요. 이 stage 의 게이트("
					+ this.session.config().gate().level().option() + ")부터 다시 확인할게요");
		if (this.session.isGit()) {
			// 고치며 새로 만든 파일도 이 stage 의 변경으로 담는다 (멈출 때 이미 있던 파일은 뺀다)
			Set<String> fixedFiles = this.session.git().untracked();
			fixedFiles.removeAll(stoppedState.untrackedAtStop());
			this.session.addCreatedFiles(fixedFiles);
		}
		if (!this.stages.resume(stopped)) {
			// 되돌린 뒤 다시 시도하는 stage 에 사용자가 만든 파일을 레시피가 만든 파일로 넘기지 않는다
			this.session.restore(stoppedState);
			return this.session.isGit() ? rollback(stopped) : stillBroken(stopped);
		}
		this.session.console().line("   통과했어요. 다음 stage 부터 이어서 진행할게요");
		this.session.clearStopped();
		return Resumed.continued("- 재개해서 " + stopped.stage() + " stage 를 고친 상태로 검증을 통과하고 이어서 진행했어요");
	}

	/** 재개 기록이 지금 작업 트리와 맞지 않으면 되돌리거나 이어서 하지 않고 멈춘다 */
	private void checkResumable(Stopped stopped) {
		Path cumulative = this.session.ws().stage(stopped.tag()).cumulativePatch();
		if (!Files.exists(cumulative)) {
			throw new MigrationException(stopped.stage() + " stage 의 누적 patch(" + cumulative
					+ ")가 없어 재개할 수 없어요. run-state.json 을 지우고 다시 실행해 주세요");
		}
		String baseRevision = this.session.state().baseRevision();
		if (!this.session.git().isAncestorOfHead(baseRevision)) {
			throw new MigrationException(
					"마이그레이션을 시작한 커밋(" + baseRevision + ")이 지금 HEAD 의 조상이 아니에요. 브랜치를 바꿨다면 원래 브랜치로 돌아가 다시 실행해 주세요");
		}
	}

	/** 컴파일이 여전히 깨지면 그 stage 전 상태로 되돌리고 그 stage 부터 다시 시도한다 */
	private Resumed rollback(Stopped stopped) {
		Path current = this.session.ws().stage(stopped.tag()).cumulativePatch();
		Path previous = (stopped.previousTag() != null)
				? this.session.ws().stage(stopped.previousTag()).cumulativePatch() : null;
		boolean rolledBack = this.session.git()
			.rollback(current, previous, this.session.state().createdFiles(), this.session.ws().tempIndex());
		if (!rolledBack) {
			// 되돌리는 명령은 출력하지 않는다 (복사해서 실행하다 작업 내용을 지우는 일이 없도록)
			throw new MigrationException(
					stopped.stage() + " stage 전 상태로 되돌리지 못했어요 (실패 이후 소스가 바뀌었어요). 작업 트리는 그대로예요. 컴파일 에러를 고치고 다시 실행해 주세요");
		}
		this.session.console().line("   {} stage 전 상태로 되돌렸어요. {} stage 부터 다시 시도할게요", stopped.stage(), stopped.stage());
		this.session.clearStopped();
		return Resumed.retryAfter(stopped.tag().order() - 1,
				"- 재개해서 " + stopped.stage() + " stage 전 상태로 되돌리고 다시 시도했어요");
	}

	/** git 저장소가 아니면 stage 전 상태로 되돌릴 수 없어, 고칠 때까지 같은 stage 에서 멈춘다 */
	private Resumed stillBroken(Stopped stopped) {
		throw new MigrationException(
				"[" + stopped.stage() + "] 컴파일 에러가 남아 있어요. 에러는 " + this.session.ws().stage(stopped.tag()).compileLog()
						+ "\n" + "   git 저장소가 아니라 stage 전 상태로 되돌릴 수 없어요. 고치고 같은 명령을 다시 실행해 주세요");
	}

	/**
	 * 재개 결과.
	 *
	 * @param active 재개했다 (남아 있는 변경은 이 마이그레이션의 변경이므로 작업 트리 검사를 하지 않는다)
	 * @param lastCompletedOrder 멈춘 stage 를 다시 시도하는 경우 그 앞 stage 의 번호, 이어서 가는 경우 null
	 * @param note history.md 에 남길 줄
	 */
	record Resumed(boolean active, @Nullable Integer lastCompletedOrder, @Nullable String note) {

		static final Resumed NONE = new Resumed(false, null, null);

		/** 멈췄던 stage 가 게이트를 통과해 다음 stage 로 이어 간다 */
		static Resumed continued(String note) {
			return new Resumed(true, null, note);
		}

		/** 멈췄던 stage 를 되돌렸고 그 stage 부터 다시 시도한다 */
		static Resumed retryAfter(int lastCompletedOrder, String note) {
			return new Resumed(true, lastCompletedOrder, note);
		}

		/** 멈췄던 stage 가 게이트를 통과해 다음 stage 로 이어 간다 (되돌려 다시 시도하는 경우가 아니다) */
		boolean continued() {
			return this.active && this.lastCompletedOrder == null;
		}

	}

}
