package com.eottabom.migration.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
			.stoppedAt(new Stopped("3.4", "01-boot-3.4", List.of("3.4"), null, Reason.BUILD, "tree"), Set.of("junk"));
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
			.stoppedAt(new Stopped("3.4", "07-boot-3.4", List.of("3.4"), "06-boot-3.3", Reason.COMPILE, null),
					Set.of());

		assertThat(stopped.stopped()).isNotNull();
		assertThat(stopped.stopped().order()).isEqualTo(7);
		assertThat(stopped.resumed().stopped()).isNull();
	}

	@Test
	void clearsLeftoverFilesOfInterruptedAttempt() throws IOException {
		MigrationWorkspace ws = MigrationWorkspace.in(this.root);
		// 재시도 중에 죽은 시도가 남긴 보관 디렉토리
		Path kept = ws.stage("01-boot-3.4").keptResults().resolve("app/build/test-results/test/TEST-demo.AppTest.xml");
		Files.createDirectories(kept.getParent());
		Files.writeString(kept, "<testsuite/>");
		Files.writeString(ws.stage("01-boot-3.4").cumulativePatch(), "patch");
		Files.writeString(ws.stage("02-boot-3.5").cumulativePatch(), "patch");

		ws.clearStage("01-boot-3.4");

		assertThat(ws.dir().resolve("01-boot-3.4")).doesNotExist();
		assertThat(ws.stage("02-boot-3.5").cumulativePatch()).exists();
		assertThat(ws.stageTags()).containsExactly("02-boot-3.5");
	}

	@Test
	void ordersStageFoldersByNumberAndFallsBackToStartVersions() throws IOException {
		MigrationWorkspace ws = MigrationWorkspace.in(this.root);
		ws.stage("10-java21");
		ws.stage("09-boot-4.1");
		Files.writeString(ws.stage("09-boot-4.1").versions(), "a:b=1\n");

		assertThat(ws.stageTags()).containsExactly("09-boot-4.1", "10-java21");
		assertThat(ws.versionsAfter("09-boot-4.1")).isEqualTo(ws.stage("09-boot-4.1").versions());
		assertThat(ws.versionsAfter("10-java21")).isEqualTo(ws.start().versions());
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
