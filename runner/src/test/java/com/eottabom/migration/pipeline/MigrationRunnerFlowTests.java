package com.eottabom.migration.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.eottabom.migration.GitFixture;
import com.eottabom.migration.MigrationException;
import com.eottabom.migration.config.GateLevel;
import com.eottabom.migration.config.JavaTarget;
import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.config.MigrationConfig.BuildSettings;
import com.eottabom.migration.config.MigrationConfig.GateSettings;
import com.eottabom.migration.config.MigrationConfig.Jdk;
import com.eottabom.migration.config.MigrationConfig.RecipeSettings;
import com.eottabom.migration.config.MigrationConfig.Target;
import com.eottabom.migration.config.Mode;
import com.eottabom.migration.gradle.FakeProjectGradle;
import com.eottabom.migration.gradle.FakeProjectGradle.BuildOutcome;
import com.eottabom.migration.gradle.InitScripts;
import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 러너의 실행 흐름을 임시 git 저장소와 가짜 대상 빌드로 검증한다. 실패 → 수정 → 재개 → 검증 → 커밋, 원본 빌드 실패와 새 실패의 구분, 작업
 * 트리 검사, 스테이징 보존.
 */
class MigrationRunnerFlowTests {

	@TempDir
	Path project;

	FakeProjectGradle fake;

	MigrationRunner runner;

	@BeforeEach
	void setUp() throws IOException {
		write(".gitignore", "build/\n.gradle/\n");
		write("settings.gradle", "rootProject.name = 'demo'\n");
		write("build.gradle", "plugins {\n    id 'org.springframework.boot' version '3.3.5'\n}\n");
		write("gradle/wrapper/gradle-wrapper.properties",
				"distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n");
		write("src/main/java/demo/App.java", "package demo;\nclass App {}\n");
		GitFixture.init(this.project);

		this.fake = new FakeProjectGradle(this.project);
		// 3.4 단계는 Boot 버전을 올리고 lombok.config 를 만들고, 3.5 단계는 버전만 올린다. dir 은 대상 프로젝트나
		// preview worktree
		this.fake.rewrites.add((dir) -> {
			replace(dir.resolve("build.gradle"), "3.3.5", "3.4.0");
			FakeProjectGradle.write(dir.resolve("lombok.config"), "config.stopBubbling = true\n");
		});
		this.fake.rewrites.add((dir) -> replace(dir.resolve("build.gradle"), "3.4.0", "3.5.0"));
		this.runner = new MigrationRunner(
				new MigrationRunner.RunnerPaths(
						new InitScripts(this.project.resolve("rewrite.init.gradle"),
								this.project.resolve("verify.init.gradle"), this.project.resolve("libs")),
						Path.of("../guides"), Path.of("../schema")),
				Logging.getLogger(MigrationRunnerFlowTests.class), (dir, javaHome) -> this.fake.at(dir));
	}

	@Test
	void previewsEveryStageInTemporaryWorktree() throws IOException {
		this.runner.run(preview("3.5"));

		assertThat(read("build.gradle")).contains("3.3.5");
		assertThat(read(".spring-boot-migrator/01-boot-3.4/preview.patch")).contains("+", "3.4.0");
		assertThat(read(".spring-boot-migrator/01-boot-3.4/preview.patch")).contains("lombok.config");
		assertThat(read(".spring-boot-migrator/02-boot-3.5/preview.patch")).contains("-", "3.4.0", "3.5.0")
			.doesNotContain("lombok.config");
		assertThat(this.project.resolve("lombok.config")).doesNotExist();
		assertThat(git("worktree", "list").lines()).hasSize(1);
	}

	@Test
	void readsBootVersionFromGradleWhenBuildFilesDoNotDeclareIt() throws IOException {
		write("build.gradle", "plugins {\n    id 'java'\n}\n");
		git("commit", "-q", "-am", "boot 버전을 BOM 으로만 받는다");

		this.runner.run(preview("3.5"));

		assertThat(read(".spring-boot-migrator/start/inspect-versions.txt")).contains("spring-boot=3.4.0");
		assertThat(this.project.resolve(".spring-boot-migrator/01-boot-3.5/preview.patch")).exists();
		assertThat(this.project.resolve(".spring-boot-migrator/01-boot-3.4/preview.patch")).doesNotExist();
	}

	@Test
	void skipsTestsOnlyInBaselineBuildWhenAsked() throws IOException {
		this.runner.run(config("3.4", Mode.STAGED, false, false, false));

		assertThat(this.fake.count("-x test")).isEqualTo(1);
		assertThat(this.fake.count("build --continue")).isEqualTo(2);
		assertThat(read(".spring-boot-migrator/history.md")).contains("gate.baselineTests=false");
	}

	@Test
	void commitsOnlyRecipeChangesWhenEachStagePasses() throws IOException {
		this.runner.run(request("3.5", true));

		assertThat(migrationCommits()).hasSize(2);
		assertThat(migrationCommits().get(1)).startsWith("chore: Spring Boot 3.4 마이그레이션");
		assertThat(git("show", "--name-only", "--format=", "HEAD~1")).contains("build.gradle", "lombok.config");
		// 빌드가 만든 추적 안 되는 파일은 커밋에 들어가지 않는다
		assertThat(git("log", "--name-only", "--format=")).doesNotContain("test-output.log");
		assertThat(read("build.gradle")).contains("3.5.0");
		assertThat(this.project.resolve(".spring-boot-migrator/result.html")).exists();
		assertThat(this.project.resolve(".spring-boot-migrator/01-boot-3.4/stage.patch")).exists();
	}

	@Test
	void resumeAfterFixedCompileRunsBuildGateBeforeCommit() throws IOException {
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.5", true))).isInstanceOf(MigrationException.class)
			.hasMessageContaining("컴파일 실패");
		assertThat(migrationCommits()).isEmpty();
		assertThat(read(".spring-boot-migrator/run-state.json")).contains("\"reason\" : \"compile\"");

		// 사용자가 고치며 새 파일을 만들었다. 재개 때 컴파일은 통과하고, 테스트/빌드 게이트가 다시 돈다
		write("src/main/java/demo/Fix.java", "package demo;\nclass Fix {}\n");
		this.runner.run(request("3.5", true));

		// 원본 빌드, 재개한 3.4 의 build 게이트, 3.5 의 build 게이트. clean 부터 하는 것은 원본 빌드뿐이다 (게이트는
		// compile 이 clean 을 한다)
		assertThat(this.fake.count("build --continue")).isEqualTo(3);
		assertThat(this.fake.count("clean build --continue")).isEqualTo(1);
		assertThat(migrationCommits()).hasSize(2);
		assertThat(git("show", "--name-only", "--format=", "HEAD~1")).contains("Fix.java", "lombok.config");
		assertThat(git("log", "--name-only", "--format=")).doesNotContain("test-output.log");
	}

	@Test
	void doesNotFinishLastStageWhenTestsFailAfterCompileFix() throws IOException {
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("컴파일 실패");
		assertThat(read(".spring-boot-migrator/result.html")).doesNotContain("boom");

		this.fake.builds.add(new BuildOutcome(true, 1, List.of()));
		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("테스트 1개 실패");
		// 안내하는 result.html 이 이번 게이트의 실패를 보여 준다
		assertThat(read(".spring-boot-migrator/result.html")).contains("boom");
		assertThat(migrationCommits()).isEmpty();
		assertThat(read(".spring-boot-migrator/run-state.json")).contains("\"reason\" : \"build\"");
	}

	@Test
	void resumeWithoutCommitIsNotBlockedByDirtyTree() throws IOException {
		this.fake.builds.add(new BuildOutcome(true, 2, List.of()));
		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("테스트 2개 실패");
		assertThat(git("status", "--porcelain")).contains("build.gradle");

		this.runner.run(request("3.5", false));

		assertThat(read("build.gradle")).contains("3.5.0");
		assertThat(this.project.resolve(".spring-boot-migrator/run-state.json")).doesNotExist();
	}

	@Test
	void finishesRunWhenResumedLastStagePasses() throws IOException {
		this.fake.builds.add(new BuildOutcome(true, 2, List.of()));
		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("테스트 2개 실패");

		this.runner.run(request("3.4", true));

		// 남은 stage 가 없어도 실행을 마무리한다 (재개 기록과 시작 ref 정리, 완료 기록, 커밋)
		assertThat(this.project.resolve(".spring-boot-migrator/run-state.json")).doesNotExist();
		assertThat(git("for-each-ref", "refs/spring-boot-migrator")).isBlank();
		assertThat(migrationCommits()).hasSize(1);
		String history = read(".spring-boot-migrator/history.md");
		assertThat(history).contains("Boot 3.3.5 → 3.4.0 완료");
		// 재개한 stage 의 한 줄이 표 머리 아래에 온다
		assertThat(history)
			.containsPattern("재개: 3\\.4 stage 의 게이트를 다시 확인\\n\\n\\| stage \\|[^\\n]*\\n\\|---[^\\n]*\\n\\| 3\\.4 \\|");
	}

	@Test
	void numbersStagesAfterHighestExistingFolder() throws IOException {
		// 지난 실행의 폴더가 중간이 비어 있어도 남은 번호와 겹치지 않는다
		write(".spring-boot-migrator/03-boot-3.2/result.md", "old");

		this.runner.run(request("3.4", false));

		assertThat(read(".spring-boot-migrator/03-boot-3.2/result.md")).isEqualTo("old");
		assertThat(this.project.resolve(".spring-boot-migrator/04-boot-3.4/result.md")).exists();
	}

	@Test
	void continuesWithoutStartVersionsAndSaysSo() throws IOException {
		this.fake.resolvesStartVersions = false;

		this.runner.run(request("3.4", false));

		assertThat(read("build.gradle")).contains("3.4.0");
		assertThat(read(".spring-boot-migrator/history.md")).contains("시작할 때 의존성 버전을 모으지 못해");
	}

	@Test
	void projectWithoutGitVerifiesStoppedStageAgainInsteadOfSkippingIt() throws IOException {
		removeGit();
		this.fake.builds.add(new BuildOutcome(true, 2, List.of()));
		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("테스트 2개 실패");
		assertThat(read(".spring-boot-migrator/run-state.json")).contains("\"reason\" : \"build\"");

		// 고치지 않고 다시 실행하면 같은 stage 의 게이트에서 다시 멈춘다 (다음 stage 로 넘어가지 않는다)
		this.fake.builds.add(new BuildOutcome(true, 1, List.of()));
		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("테스트 1개 실패");
		assertThat(read("build.gradle")).contains("3.4.0");

		this.runner.run(request("3.5", false));

		assertThat(read("build.gradle")).contains("3.5.0");
		assertThat(this.project.resolve(".spring-boot-migrator/run-state.json")).doesNotExist();
	}

	@Test
	void projectWithoutGitStopsAgainWhileCompileIsStillBroken() throws IOException {
		removeGit();
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.4", false))).hasMessageContaining("컴파일 실패")
			.hasMessageContaining("되돌릴 수 없어요");

		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.4", false))).hasMessageContaining("컴파일 에러가 남아 있어요");

		// 고치면 이 stage 의 게이트를 이어서 확인하고 끝낸다
		this.runner.run(request("3.4", false));
		assertThat(this.project.resolve(".spring-boot-migrator/run-state.json")).doesNotExist();
	}

	@Test
	void refusesToResumeRecordFromAnotherProject() throws IOException {
		stopAtFirstStageWithFailingTests();
		write(".spring-boot-migrator/run-state.json", read(".spring-boot-migrator/run-state.json")
			.replaceAll("\"project\" : \"[^\"]*\"", "\"project\" : \"/somewhere/else\""));

		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("다른 프로젝트");
	}

	@Test
	void refusesToResumeWhenStagePatchIsMissing() throws IOException {
		stopAtFirstStageWithFailingTests();
		Files.delete(this.project.resolve(".spring-boot-migrator/01-boot-3.4/cumulative.patch"));

		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("누적 patch");
	}

	@Test
	void refusesToResumeWhenBaseCommitIsNotInHistory() throws IOException {
		stopAtFirstStageWithFailingTests();
		String unrelated = git("commit-tree", git("rev-parse", "HEAD^{tree}").trim(), "-m", "unrelated").trim();
		write(".spring-boot-migrator/run-state.json", read(".spring-boot-migrator/run-state.json")
			.replaceAll("\"baseRevision\" : \"[^\"]*\"", "\"baseRevision\" : \"" + unrelated + "\""));

		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("HEAD 의 조상이 아니에요");
	}

	private void stopAtFirstStageWithFailingTests() {
		this.fake.builds.add(new BuildOutcome(true, 2, List.of()));
		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("테스트 2개 실패");
	}

	@Test
	void passesBaselineFailuresButStopsOnNewFailedTasks() throws IOException {
		this.fake.baseline = new BuildOutcome(false, 0, List.of(":app:bootJar"));
		this.fake.builds.add(new BuildOutcome(false, 0, List.of(":app:bootJar")));
		this.fake.builds.add(new BuildOutcome(false, 0, List.of(":app:bootJar", ":app:checkstyleMain")));

		assertThatThrownBy(() -> this.runner.run(request("3.5", true))).hasMessageContaining("[3.5]")
			.hasMessageContaining(":app:checkstyleMain")
			.hasMessageNotContaining(":app:bootJar,");
		assertThat(migrationCommits()).hasSize(1);
	}

	@Test
	void passesTestsThatAlreadyFailedButStopsOnNewTestFailures() throws IOException {
		this.fake.baseline = new BuildOutcome(true, 2, List.of());
		this.fake.builds.add(new BuildOutcome(true, 2, List.of()));
		this.fake.builds.add(new BuildOutcome(true, 3, List.of()));

		assertThatThrownBy(() -> this.runner.run(request("3.5", true))).hasMessageContaining("[3.5]")
			.hasMessageContaining("테스트 1개 실패");
		assertThat(migrationCommits()).hasSize(1);
		assertThat(read(".spring-boot-migrator/run-state.json")).contains("demo.AppTest#t0", "demo.AppTest#t1");
	}

	@Test
	void ignoresStaleTestResultsOutsideTheBuild() throws IOException {
		// 따로 빌드되는 중첩 프로젝트에 예전 실패 결과가 남아 있다 (이번 빌드의 clean 이 지우지 않는다)
		Path stale = this.project.resolve("tools/proxy/build/test-results/test/TEST-old.ProxyTest.xml");
		write("tools/proxy/build/test-results/test/TEST-old.ProxyTest.xml", """
				<testsuite name="old.ProxyTest" tests="1" failures="1" errors="0">
				  <testcase classname="old.ProxyTest" name="old"><failure message="x">x</failure></testcase>
				</testsuite>
				""");
		Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
		write(".gitignore", read(".gitignore") + "tools/\n");
		git("add", "-A");
		git("commit", "-q", "-m", "ignore tools");

		this.runner.run(request("3.4", true));

		assertThat(migrationCommits()).hasSize(1);
		assertThat(read(".spring-boot-migrator/01-boot-3.4/result.md")).doesNotContain("old.ProxyTest");
	}

	@Test
	void passesStageWhenFailedTestPassesOnRetry() throws IOException {
		this.fake.builds.add(new BuildOutcome(true, 1, List.of()));
		this.fake.retries.add(BuildOutcome.pass());

		this.runner.run(request("3.4", true));

		assertThat(this.fake.count("test --continue --tests demo.AppTest*")).isEqualTo(1);
		assertThat(migrationCommits()).hasSize(1);
		assertThat(read(".spring-boot-migrator/01-boot-3.4/result.json")).contains("demo.AppTest#t0");
		assertThat(read(".spring-boot-migrator/01-boot-3.4/result.md")).contains("## 다시 돌려 통과한 테스트 (1건)");
	}

	@Test
	void stopsWhenFailedTestStillFailsOnRetry() {
		this.fake.builds.add(new BuildOutcome(true, 1, List.of()));
		this.fake.retries.add(new BuildOutcome(true, 1, List.of()));

		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("테스트 1개 실패");
	}

	@Test
	void refusesToStartWhenOriginalSourceDoesNotCompile() {
		this.fake.baseline = new BuildOutcome(false, 0, List.of(":app:compileJava"));

		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("현재 소스가 컴파일되지 않아요");
	}

	@Test
	void stopsOnBuildFailureWithUnknownCause() {
		this.fake.baseline = new BuildOutcome(false, 0, List.of(":app:bootJar"));
		this.fake.builds.add(new BuildOutcome(false, 0, List.of()));

		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("실패한 태스크를 찾지 못함");
	}

	@Test
	void revertsAndRetriesStageWhenCompileStillFails() throws IOException {
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.4", false))).hasMessageContaining("컴파일 실패");
		assertThat(read("build.gradle")).contains("3.4.0");

		// 재개 때 컴파일이 여전히 실패 → 되돌리고 3.4 를 다시 실행 (이번에는 레시피가 바뀌어 컴파일된다고 가정)
		this.fake.compiles.add(false);
		this.fake.rewrites.addFirst((dir) -> replace(dir.resolve("build.gradle"), "3.3.5", "3.4.1"));
		this.runner.run(request("3.4", false));

		assertThat(read("build.gradle")).contains("3.4.1");
		assertThat(this.project.resolve("lombok.config")).doesNotExist();
	}

	@Test
	void refusesToStartWithUncommittedChanges() throws IOException {
		write("src/main/java/demo/App.java", "package demo;\nclass App { int x; }\n");

		assertThatThrownBy(() -> this.runner.run(request("3.4", false))).hasMessageContaining("--allow-dirty");
		assertThatThrownBy(() -> this.runner.run(request("3.4", true))).hasMessageContaining("--commit 은 작업 트리가 깨끗해야");
	}

	@Test
	void previewDoesNotRecordRunState() throws IOException {
		this.runner.run(preview("3.5"));
		git("commit", "-q", "--allow-empty", "-m", "preview 뒤의 사용자 커밋");

		assertThat(this.project.resolve(".spring-boot-migrator/run-state.json")).doesNotExist();
	}

	@Test
	void freshRunDoesNotReuseBaseOfPreviousRun() throws IOException {
		this.runner.run(request("3.4", true));
		assertThat(this.project.resolve(".spring-boot-migrator/run-state.json")).doesNotExist();
		write("src/main/java/demo/Later.java", "package demo;\nclass Later {}\n");
		git("add", "-A");
		git("commit", "-q", "-m", "마이그레이션 뒤 사용자 커밋");

		// 다음 실행의 첫 단계가 컴파일로 멈췄다가 되돌려도 사이의 커밋은 그대로다
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("컴파일 실패");
		this.fake.compiles.add(false);
		this.fake.rewrites.addFirst((dir) -> replace(dir.resolve("build.gradle"), "3.4.0", "3.5.1"));
		this.runner.run(request("3.5", false));

		assertThat(read("build.gradle")).contains("3.5.1");
		assertThat(read("lombok.config")).contains("stopBubbling");
		assertThat(read("src/main/java/demo/Later.java")).contains("class Later");
	}

	@Test
	void rollbackOfFirstStageKeepsChangesThatExistedBeforeTheRun() throws IOException {
		write("src/main/java/demo/App.java", "package demo;\nclass App { int dirty; }\n");
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(dirty("3.4"))).hasMessageContaining("컴파일 실패");

		this.fake.compiles.add(false);
		this.fake.rewrites.addFirst((dir) -> replace(dir.resolve("build.gradle"), "3.3.5", "3.4.1"));
		this.runner.run(dirty("3.4"));

		assertThat(read("build.gradle")).contains("3.4.1");
		assertThat(read("src/main/java/demo/App.java")).contains("int dirty;");
	}

	@Test
	void resumedStageKeepsUserFixWhenNextStageIsRolledBack() throws IOException {
		// 3.4 가 테스트로 멈추고, 사용자가 고친 뒤 재개해서 통과한다
		this.fake.builds.add(new BuildOutcome(true, 1, List.of()));
		this.fake.retries.add(new BuildOutcome(true, 1, List.of()));
		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("테스트 1개 실패");
		write("src/main/java/demo/Fix.java", "package demo;\nclass Fix {}\n");
		replace(this.project.resolve("src/main/java/demo/App.java"), "class App {}", "class App { int fixed; }");
		// 재개는 통과하고(3.4 의 compile 게이트) 3.5 는 컴파일로 멈춘다
		this.fake.compiles.add(true);
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.5", false))).hasMessageContaining("컴파일 실패");
		assertThat(read(".spring-boot-migrator/01-boot-3.4/cumulative.patch")).contains("Fix.java", "int fixed;");

		// 3.5 를 되돌려도 3.4 에서 고친 내용은 남는다
		this.fake.compiles.add(false);
		this.fake.rewrites.addFirst((dir) -> replace(dir.resolve("build.gradle"), "3.4.0", "3.5.1"));
		this.runner.run(request("3.5", false));

		assertThat(read("build.gradle")).contains("3.5.1");
		assertThat(read("src/main/java/demo/Fix.java")).contains("class Fix");
		assertThat(read("src/main/java/demo/App.java")).contains("int fixed;");
	}

	@Test
	void stopsWhenCommitFails() throws IOException {
		write(".git/hooks/pre-commit", "#!/bin/sh\necho 'hook 이 막았다' >&2\nexit 1\n");
		this.project.resolve(".git/hooks/pre-commit").toFile().setExecutable(true);

		assertThatThrownBy(() -> this.runner.run(request("3.5", true))).hasMessageContaining("commit 실패");
		assertThat(read(".spring-boot-migrator/01-boot-3.4/commit.log")).contains("hook 이 막았다");
		assertThat(read("build.gradle")).contains("3.4.0");
	}

	@Test
	void resumesAfterUserCommitsWhatTheHookRejected() throws IOException {
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.5", true))).hasMessageContaining("컴파일 실패");

		// 고친 뒤 재개하면 게이트는 통과하지만 hook 이 커밋을 막는다
		write("src/main/java/demo/Fix.java", "package demo;\nclass Fix {}\n");
		write(".git/hooks/pre-commit", "#!/bin/sh\nexit 1\n");
		this.project.resolve(".git/hooks/pre-commit").toFile().setExecutable(true);
		assertThatThrownBy(() -> this.runner.run(request("3.5", true))).hasMessageContaining("commit 실패");

		// 안내대로 직접 커밋하고 다시 실행하면 담을 변경이 없는 커밋은 건너뛰고 다음 단계로 간다
		Files.delete(this.project.resolve(".git/hooks/pre-commit"));
		git("add", "build.gradle", "lombok.config", "src/main/java/demo/Fix.java");
		git("commit", "-q", "-m", "직접 커밋한 3.4 마이그레이션");
		this.runner.run(request("3.5", true));

		assertThat(read("build.gradle")).contains("3.5.0");
		assertThat(this.project.resolve(".spring-boot-migrator/run-state.json")).doesNotExist();
		assertThat(migrationCommits()).hasSize(2);
	}

	@Test
	void replacesDeprecatedApiWithGuideRecipeAndCompilesAgain() throws IOException {
		this.fake.compileWarnings.add(this.project.resolve("src/main/java/demo/App.java")
				+ ":3: warning: [removal] Integer(int) in Integer has been deprecated and marked for removal");
		this.fake.compileWarnings.add("");

		this.runner.run(request("3.4", false));

		assertThat(this.fake.count("rewriteRun migration.assembled.Deprecations_01_boot_3_4")).isEqualTo(1);
		assertThat(this.fake.count("compileJava")).isEqualTo(2);
		assertThat(read(".spring-boot-migrator/01-boot-3.4/result.json"))
			.contains("org.openrewrite.staticanalysis.PrimitiveWrapperClassConstructorToValueOf");
		assertThat(read(".spring-boot-migrator/01-boot-3.4/compile-before-deprecations.log")).contains("[removal]");
		assertThat(read(".spring-boot-migrator/01-boot-3.4/result.md")).contains("## deprecated API 대체");
	}

	@Test
	void resumedStageRunsDeprecationStepAndReportsFreshCompileLog() throws IOException {
		this.fake.compiles.add(false);
		assertThatThrownBy(() -> this.runner.run(request("3.4", false))).hasMessageContaining("컴파일 실패");
		assertThat(read(".spring-boot-migrator/01-boot-3.4/compile.log")).contains("Compilation failed");

		// 고친 뒤의 컴파일에서 나온 경고로 대체 레시피를 돌리고, 결과도 그 컴파일 로그로 만든다
		this.fake.compileWarnings.add(this.project.resolve("src/main/java/demo/App.java")
				+ ":3: warning: [removal] Integer(int) in Integer has been deprecated and marked for removal");
		this.fake.compileWarnings.add(this.project.resolve("src/main/java/demo/App.java")
				+ ":5: warning: [removal] old() in App has been deprecated and marked for removal");
		this.runner.run(request("3.4", false));

		assertThat(this.fake.count("rewriteRun migration.assembled.Deprecations_01_boot_3_4")).isEqualTo(1);
		assertThat(read(".spring-boot-migrator/01-boot-3.4/compile.log")).doesNotContain("Compilation failed");
		assertThat(read(".spring-boot-migrator/01-boot-3.4/result.md")).contains("## deprecated API 대체")
			.contains("old() in App has been deprecated");
		assertThat(this.project.resolve(".spring-boot-migrator/run-state.json")).doesNotExist();
	}

	@Test
	void keepsDeprecatedApiWithoutGuideRecipeInResult() throws IOException {
		this.fake.compileWarnings.add(this.project.resolve("src/main/java/demo/App.java")
				+ ":3: warning: [removal] old() in App has been deprecated and marked for removal");

		this.runner.run(request("3.4", false));

		assertThat(this.fake.count("Deprecations_")).isZero();
		assertThat(read(".spring-boot-migrator/01-boot-3.4/result.md")).contains("old() in App has been deprecated");
	}

	@Test
	void runsAllStagesAtOnceWithOneGate() throws IOException {
		this.fake.rewrites.clear();
		this.fake.rewrites.add((dir) -> replace(dir.resolve("build.gradle"), "3.3.5", "3.5.0"));

		this.runner.run(config("3.5", Mode.ALL, true, false, true));

		assertThat(this.fake.count("rewriteRun migration.assembled.Stage_01_boot_3_5")).isEqualTo(1);
		assertThat(read(".rewrite/rewrite.assembled.yml")).contains("com.eottabom.rewrite.stage.Boot_3_4",
				"com.eottabom.rewrite.stage.Boot_3_5");
		assertThat(migrationCommits()).hasSize(1);
		assertThat(read(".spring-boot-migrator/01-boot-3.5/result.json")).contains("\"covers\" : [ \"3.4\", \"3.5\" ]");
	}

	@Test
	void scanOutputDoesNotMakeTheTreeDirty() throws IOException {
		write(".spring-boot-migrator/scan/versions.log", "scan\n");

		this.runner.run(request("3.4", false));

		assertThat(read("build.gradle")).contains("3.4.0");
	}

	private void removeGit() throws IOException {
		try (Stream<Path> files = Files.walk(this.project.resolve(".git"))) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		}
	}

	private MigrationConfig dirty(String target) {
		return config(target, Mode.STAGED, false, true, true);
	}

	private MigrationConfig request(String target, boolean commit) {
		return request(target, commit, false);
	}

	private MigrationConfig preview(String target) {
		return request(target, false, true);
	}

	private MigrationConfig request(String target, boolean commit, boolean preview) {
		return config(target, preview ? Mode.PREVIEW : Mode.STAGED, commit, false, true);
	}

	/** gate=build, java 유지, JAVA_HOME 그대로, 프로젝트 레시피 없음 */
	private MigrationConfig config(String target, Mode mode, boolean commit, boolean allowDirty,
			boolean baselineTests) {
		return new MigrationConfig(this.project, new Target(target, JavaTarget.parse("keep")), mode,
				new GateSettings(GateLevel.BUILD, MigrationConfig.DEFAULT_TEST_RETRIES, baselineTests),
				new RecipeSettings(true, false), new BuildSettings(Jdk.CURRENT, null, Duration.ZERO), commit,
				allowDirty);
	}

	private List<String> migrationCommits() throws IOException {
		return git("log", "--format=%s").lines().filter((subject) -> subject.contains("마이그레이션")).toList();
	}

	private String git(String... args) {
		return GitFixture.git(this.project, args);
	}

	private void write(String path, String content) {
		FakeProjectGradle.write(this.project.resolve(path), content);
	}

	private String read(String path) throws IOException {
		return Files.readString(this.project.resolve(path));
	}

	private static void replace(Path file, String from, String to) {
		try {
			FakeProjectGradle.write(file, Files.readString(file).replace(from, to));
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
