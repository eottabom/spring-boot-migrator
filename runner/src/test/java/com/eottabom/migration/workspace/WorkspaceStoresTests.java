package com.eottabom.migration.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.eottabom.migration.stage.StageId;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.workspace.RunState.Baseline;
import com.eottabom.migration.workspace.RunState.Reason;
import com.eottabom.migration.workspace.RunState.Stopped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceStoresTests {

	private static final Path SCHEMA = Path.of("../schema");

	@TempDir
	Path root;

	@Test
	void roundTripsRunStateAndRejectsStateOfAnotherProject() throws IOException {
		MigrationWorkspace ws = MigrationWorkspace.in(this.root.resolve("one"));
		RunStateStore store = RunStateStore.of(ws, SCHEMA);
		RunState state = RunState.start(store.project(), "head", "start", "3.3.5")
			.withBaseline(new Baseline(Set.of(":app:check"), Set.of("app:Demo#works")))
			.withCreatedFiles(Set.of("lombok.config"))
			.stoppedAt(new Stopped(StageId.boot("3.4"), StageTag.parse("01-boot-3.4"), List.of(StageId.boot("3.4")),
					null, Reason.BUILD, "tree"), Set.of("junk"));
		store.write(state);

		assertThat(store.read()).contains(state);
		assertThat(Files.readString(ws.runState())).contains("\"reason\" : \"build\"");

		MigrationWorkspace other = MigrationWorkspace.in(this.root.resolve("two"));
		Files.copy(ws.runState(), other.runState());
		assertThatThrownBy(() -> RunStateStore.of(other, SCHEMA).read()).hasMessageContaining("다른 프로젝트");

		store.delete();
		assertThat(store.read()).isEmpty();
	}

	@Test
	void rejectsRunStateThatDoesNotMatchSchema() throws IOException {
		MigrationWorkspace ws = MigrationWorkspace.in(this.root);
		Files.writeString(ws.runState(), "{\"version\": 0}");

		assertThatThrownBy(() -> RunStateStore.of(ws, SCHEMA).read()).hasMessageContaining("run-state.schema.json");
		Files.writeString(ws.runState(), "{");
		assertThatThrownBy(() -> RunStateStore.of(ws, SCHEMA).read()).hasMessageContaining("을 읽지 못했어요");
	}

	@Test
	void resumedStateForgetsWhereItStopped() {
		RunState stopped = RunState.start("p", "h", "s", null)
			.stoppedAt(new Stopped(StageId.boot("3.4"), StageTag.parse("07-boot-3.4"), List.of(StageId.boot("3.4")),
					StageTag.parse("06-boot-3.3"), Reason.COMPILE, null), Set.of());

		assertThat(stopped.stopped()).isNotNull();
		assertThat(stopped.stopped().tag().order()).isEqualTo(7);
		assertThat(stopped.resumed().stopped()).isNull();
	}

	@Test
	void clearsLeftoverFilesOfInterruptedAttempt() throws IOException {
		MigrationWorkspace ws = MigrationWorkspace.in(this.root);
		// 재시도 중에 죽은 시도가 남긴 보관 디렉토리
		Path kept = ws.stage(StageTag.parse("01-boot-3.4"))
			.keptResults()
			.resolve("app/build/test-results/test/TEST-demo.AppTest.xml");
		Files.createDirectories(kept.getParent());
		Files.writeString(kept, "<testsuite/>");
		Files.writeString(ws.stage(StageTag.parse("01-boot-3.4")).cumulativePatch(), "patch");
		Files.writeString(ws.stage(StageTag.parse("02-boot-3.5")).cumulativePatch(), "patch");

		ws.clearStage(StageTag.parse("01-boot-3.4"));

		assertThat(ws.dir().resolve("01-boot-3.4")).doesNotExist();
		assertThat(ws.stage(StageTag.parse("02-boot-3.5")).cumulativePatch()).exists();
		assertThat(ws.stageTags()).containsExactly(StageTag.parse("02-boot-3.5"));
	}

	@Test
	void ordersStageFoldersByNumberAndFallsBackToStartVersions() throws IOException {
		MigrationWorkspace ws = MigrationWorkspace.in(this.root);
		assertThat(ws.lastStageOrder()).isZero();
		ws.stage(StageTag.parse("10-java21"));
		ws.stage(StageTag.parse("09-boot-4.1"));
		Files.writeString(ws.stage(StageTag.parse("09-boot-4.1")).versions(), "a:b=1\n");

		assertThat(ws.stageTags()).containsExactly(StageTag.parse("09-boot-4.1"), StageTag.parse("10-java21"));
		// 폴더 개수(2)가 아니라 가장 큰 번호다
		assertThat(ws.lastStageOrder()).isEqualTo(10);
		assertThat(ws.versionsAfter(StageTag.parse("09-boot-4.1")))
			.isEqualTo(ws.stage(StageTag.parse("09-boot-4.1")).versions());
		assertThat(ws.versionsAfter(StageTag.parse("10-java21"))).isEqualTo(ws.start().versions());
		assertThat(ws.versionsAfter(null)).isEqualTo(ws.start().versions());
	}

	@Test
	void appendsHistoryWithOneHeader() throws IOException {
		MigrationWorkspace ws = MigrationWorkspace.in(this.root);
		ws.appendHistory("demo", "first\n");
		ws.appendHistory("demo", "second\n");
		assertThat(Files.readString(ws.history())).isEqualTo("# demo 마이그레이션 기록\n\nfirst\nsecond\n");
	}

}
