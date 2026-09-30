package com.eottabom.migration.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.eottabom.migration.workspace.RunState;
import com.eottabom.migration.workspace.RunState.Reason;
import com.eottabom.migration.workspace.RunState.Stopped;
import org.gradle.api.GradleException;
import org.jspecify.annotations.Nullable;

/**
 * 게이트에서 멈춘 기록(run-state.json)으로 이어서 한다. 컴파일로 멈췄는데 아직 깨져 있으면 그 stage 전 상태로 되돌려 다시 시도하고,
 * 고쳤으면 게이트를 다시 확인해 통과할 때만 다음 stage 로 간다.
 */
record Resumption(RunSession session, StageRunner stages) {

	Resumed resume() {
		Optional<RunState> saved;
		try {
			saved = session().store().read();
		}
		catch (IllegalStateException ex) {
			throw new GradleException(String.valueOf(ex.getMessage()), ex);
		}
		if (saved.isEmpty() || saved.get().stopped() == null) {
			return Resumed.NONE;
		}
		session().state(saved.get());
		Stopped stopped = Objects.requireNonNull(saved.get().stopped());
		if (session().isGit()) {
			checkResumable(stopped);
		}
		session().continueAfter(stopped.previousTag());
		if (stopped.reason() == Reason.COMPILE) {
			session().console().heading("[재개] 지난 실행이 " + stopped.stage() + " stage 에서 컴파일이 실패해 멈췄어요");
			if (!session().gradle()
				.run(session().ws().start().resumeCompileLog(), List.of("clean", "compileJava", "compileTestJava"))) {
				return session().isGit() ? rollback(stopped) : stillBroken(stopped);
			}
			session().console()
				.line("   이제 컴파일돼요. 이 stage 의 게이트({})를 이어서 확인할게요", session().config().gate().level().option());
		}
		else {
			session().console()
				.heading("[재개] 지난 실행이 " + stopped.stage() + " stage 에서 테스트나 빌드가 실패해 멈췄어요. 이 stage 검증부터 다시 할게요");
		}
		// 고치며 새로 만든 파일도 이 stage 의 변경으로 담는다 (멈출 때 이미 있던 파일은 뺀다)
		if (session().isGit()) {
			Set<String> fixedFiles = session().git().untracked();
			fixedFiles.removeAll(session().state().untrackedAtStop());
			session().state(session().state().withCreatedFiles(fixedFiles));
		}
		session().history().resuming(stopped.stage());
		stages().resume(stopped);
		session().console().line("   통과했어요. 다음 stage 부터 이어서 진행할게요");
		session().state(session().state().resumed());
		return new Resumed(true, null, "- 재개해서 " + stopped.stage() + " stage 를 고친 상태로 검증을 통과하고 이어서 진행했어요");
	}

	/** 재개 기록이 지금 작업 트리와 맞지 않으면 되돌리거나 이어서 하지 않고 멈춘다 */
	private void checkResumable(Stopped stopped) {
		Path cumulative = session().ws().stage(stopped.tag()).cumulativePatch();
		if (!Files.exists(cumulative)) {
			throw new GradleException(stopped.stage() + " stage 의 누적 patch(" + cumulative
					+ ")가 없어 재개할 수 없어요. run-state.json 을 지우고 다시 실행해 주세요");
		}
		if (!session().git().isAncestorOfHead(session().state().baseRevision())) {
			throw new GradleException("마이그레이션을 시작한 커밋(" + session().state().baseRevision()
					+ ")이 지금 HEAD 의 조상이 아니에요. 브랜치를 바꿨다면 원래 브랜치로 돌아가 다시 실행해 주세요");
		}
	}

	/** 컴파일이 여전히 깨지면 그 stage 전 상태로 되돌리고 그 stage 부터 다시 시도한다 */
	private Resumed rollback(Stopped stopped) {
		Path previous = (stopped.previousTag() != null) ? session().ws().stage(stopped.previousTag()).cumulativePatch()
				: null;
		if (!session().git()
			.rollback(session().ws().stage(stopped.tag()).cumulativePatch(), previous, session().state().createdFiles(),
					session().ws().tempIndex())) {
			// 되돌리는 명령은 출력하지 않는다 (복사해서 실행하다 작업 내용을 지우는 일이 없도록)
			throw new GradleException(
					stopped.stage() + " stage 전 상태로 되돌리지 못했어요 (실패 이후 소스가 바뀌었어요). 작업 트리는 그대로예요. 컴파일 에러를 고치고 다시 실행해 주세요");
		}
		session().console().line("   {} stage 전 상태로 되돌렸어요. {} stage 부터 다시 시도할게요", stopped.stage(), stopped.stage());
		session().state(session().state().resumed());
		return new Resumed(true, stopped.tag().order() - 1, "- 재개해서 " + stopped.stage() + " stage 전 상태로 되돌리고 다시 시도했어요");
	}

	/** git 저장소가 아니면 stage 전 상태로 되돌릴 수 없어, 고칠 때까지 같은 stage 에서 멈춘다 */
	private Resumed stillBroken(Stopped stopped) {
		throw new GradleException(
				"[" + stopped.stage() + "] 컴파일 에러가 남아 있어요. 에러는 " + session().ws().start().resumeCompileLog() + "\n"
						+ "   git 저장소가 아니라 stage 전 상태로 되돌릴 수 없어요. 고치고 같은 명령을 다시 실행해 주세요");
	}

	/**
	 * 재개 결과.
	 *
	 * @param active 재개했다 (남아 있는 변경은 이 마이그레이션의 변경이므로 작업 트리 검사를 하지 않는다)
	 * @param lastCompletedOrder 멈춘 stage 를 다시 시도하는 경우 그 앞 stage 의 번호, 이어서 가는 경우 null
	 */
	record Resumed(boolean active, @Nullable Integer lastCompletedOrder, @Nullable String note) {

		static final Resumed NONE = new Resumed(false, null, null);

		/** 멈췄던 stage 가 게이트를 통과해 다음 stage 로 이어 간다 (되돌려 다시 시도하는 경우가 아니다) */
		boolean continued() {
			return this.active && this.lastCompletedOrder == null;
		}

	}

}
