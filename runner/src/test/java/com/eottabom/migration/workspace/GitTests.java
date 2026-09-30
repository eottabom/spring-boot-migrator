package com.eottabom.migration.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.eottabom.migration.GitFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class GitTests {

	@TempDir
	Path temp;

	Path repo;

	@BeforeEach
	void setUp() {
		this.repo = this.temp.resolve("repo");
		GitFixture.write(this.repo.resolve("build.gradle"), "plugins {}\n");
		GitFixture.write(this.repo.resolve("src/main/java/demo/App.java"), "package demo;\nclass App {}\n");
		GitFixture.init(this.repo);
	}

	@Test
	void keepsUserStagingWhenWritingPatch() throws IOException {
		// --allow-dirty 로 시작한 사용자가 일부만 스테이징해 둔 상태
		GitFixture.write(this.repo.resolve("src/main/java/demo/App.java"),
				"package demo;\nclass App { int staged; }\n");
		GitFixture.git(this.repo, "add", "src/main/java/demo/App.java");
		GitFixture.write(this.repo.resolve("build.gradle"), "plugins {}\n// not staged\n");
		String staged = GitFixture.git(this.repo, "diff", "--cached", "--name-only");
		String unstaged = GitFixture.git(this.repo, "diff", "--name-only");

		diffSince(new Git(this.repo), GitFixture.git(this.repo, "rev-parse", "HEAD").trim(),
				this.repo.resolve("out.patch"), List.of(), this.repo.resolve(".index-tmp"));

		assertThat(GitFixture.git(this.repo, "diff", "--cached", "--name-only")).isEqualTo(staged)
			.contains("App.java")
			.doesNotContain("build.gradle");
		assertThat(GitFixture.git(this.repo, "diff", "--name-only")).isEqualTo(unstaged);
		assertThat(Files.readString(this.repo.resolve("out.patch"))).contains("App.java", "build.gradle");
	}

	@Test
	void untrackedIsModifiableEvenWhenGitFails(@TempDir Path notARepository) {
		Set<String> untracked = new Git(notARepository).untracked();

		assertThat(untracked).isEmpty();
		// 호출하는 쪽이 removeAll 로 지운다
		assertThat(untracked.removeAll(Set.of("a"))).isFalse();
	}

	@Test
	void skipsCommitWhenNothingChanged() {
		Git git = new Git(this.repo);
		String head = git.head();

		assertThat(git.commit(List.of(), "chore: nothing", "body", null)).isEqualTo(Git.CommitResult.NOTHING);
		assertThat(git.head()).isEqualTo(head);

		GitFixture.write(this.repo.resolve("build.gradle"), "plugins {}\n// changed\n");
		assertThat(git.commit(List.of(), "chore: changed", "body", null)).isEqualTo(Git.CommitResult.COMMITTED);
		assertThat(git.head()).isNotEqualTo(head);
	}

	@Test
	void reportsWhetherWorktreeWasRemoved() {
		Path worktree = this.temp.resolve("preview");
		Git git = new Git(this.repo);
		assertThat(git.addWorktree(worktree)).isTrue();

		assertThat(git.removeWorktree(worktree)).isTrue();
		assertThat(worktree).doesNotExist();
		assertThat(git.removeWorktree(worktree)).isFalse();
	}

	@Test
	void excludesInLinkedWorktreeWhereGitIsAFile() {
		Path worktree = this.temp.resolve("worktree");
		GitFixture.git(this.repo, "worktree", "add", "-q", "-b", "feature", worktree.toString());
		GitFixture.write(worktree.resolve(".spring-boot-migrator/result.md"), "report\n");

		new Git(worktree).exclude(".spring-boot-migrator/");

		assertThat(Files.isRegularFile(worktree.resolve(".git"))).isTrue();
		assertThat(GitFixture.git(worktree, "status", "--porcelain")).doesNotContain(".spring-boot-migrator");
	}

	@Test
	void rollbackRestoresWorkingTreeWhenPreviousPatchNoLongerApplies() throws IOException {
		Git git = new Git(this.repo);
		String base = GitFixture.git(this.repo, "rev-parse", "HEAD").trim();
		GitFixture.write(this.repo.resolve("build.gradle"), "plugins { id 'boot' version '3.4' }\n");
		diffSince(git, base, this.temp.resolve("01.patch"), List.of(), this.temp.resolve("idx"));
		GitFixture.write(this.repo.resolve("build.gradle"), "plugins { id 'boot' version '3.5' }\n");
		GitFixture.write(this.repo.resolve("lombok.config"), "config.stopBubbling = true\n");
		diffSince(git, base, this.temp.resolve("02.patch"), List.of("lombok.config"), this.temp.resolve("idx"));
		// 이전 단계 patch 가 더는 맞지 않는 상태 (다른 파일 내용으로 바뀜)
		Files.writeString(this.temp.resolve("01.patch"),
				Files.readString(this.temp.resolve("01.patch")).replace("plugins {}", "plugins { changed }"));

		boolean rolledBack = git.rollback(this.temp.resolve("02.patch"), this.temp.resolve("01.patch"),
				List.of("lombok.config"), this.temp.resolve("idx"));

		assertThat(rolledBack).isFalse();
		assertThat(Files.readString(this.repo.resolve("build.gradle"))).contains("3.5");
		assertThat(this.repo.resolve("lombok.config")).exists();
	}

	@Test
	void rollbackReturnsToPreviousStage() throws IOException {
		Git git = new Git(this.repo);
		String base = GitFixture.git(this.repo, "rev-parse", "HEAD").trim();
		GitFixture.write(this.repo.resolve("build.gradle"), "plugins { id 'boot' version '3.4' }\n");
		diffSince(git, base, this.temp.resolve("01.patch"), List.of(), this.temp.resolve("idx"));
		GitFixture.write(this.repo.resolve("build.gradle"), "plugins { id 'boot' version '3.5' }\n");
		GitFixture.write(this.repo.resolve("lombok.config"), "config.stopBubbling = true\n");
		diffSince(git, base, this.temp.resolve("02.patch"), List.of("lombok.config"), this.temp.resolve("idx"));

		boolean rolledBack = git.rollback(this.temp.resolve("02.patch"), this.temp.resolve("01.patch"),
				List.of("lombok.config"), this.temp.resolve("idx"));

		assertThat(rolledBack).isTrue();
		assertThat(Files.readString(this.repo.resolve("build.gradle"))).contains("3.4");
		assertThat(this.repo.resolve("lombok.config")).doesNotExist();
	}

	/** base 대비 누적 patch 를 러너처럼 만든다 (작업 트리 스냅샷 → tree diff) */
	private static void diffSince(Git git, String base, Path patch, List<String> created, Path tempIndex) {
		String tree = git.snapshotTree(created, tempIndex);
		assertThat(tree).isNotNull();
		assertThat(git.diffTrees(base, tree, patch)).isTrue();
	}

}
