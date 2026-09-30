package com.eottabom.migration.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eottabom.migration.io.Processes;
import org.jspecify.annotations.Nullable;

/** 대상 프로젝트의 git 조작. */
public record Git(Path dir) {

	public @Nullable String head() {
		String out = Processes.capture(this.dir, "git", "rev-parse", "HEAD");
		return (out != null) ? out.trim() : null;
	}

	/** base 가 HEAD 의 조상이다 (같은 브랜치 흐름에서 이어서 작업하고 있다) */
	public boolean isAncestorOfHead(String base) {
		return run("git", "merge-base", "--is-ancestor", base, "HEAD");
	}

	/** 이 디렉토리를 patch 스냅샷 / 커밋 대상에서 뺀다 (info/exclude, worktree 면 저장소 공용 파일). */
	public void exclude(String entry) {
		String path = Processes.capture(this.dir, "git", "rev-parse", "--git-path", "info/exclude");
		if (path == null || path.isBlank()) {
			throw new IllegalStateException("git 의 info/exclude 경로를 찾지 못했어요: " + this.dir);
		}
		Path exclude = this.dir.resolve(path.trim());
		try {
			List<String> lines = Files.exists(exclude) ? Files.readAllLines(exclude) : List.of();
			if (!lines.contains(entry)) {
				Files.createDirectories(exclude.getParent());
				Files.writeString(exclude, (lines.isEmpty() ? "" : String.join("\n", lines) + "\n") + entry + "\n");
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** .gitignore 에 걸리지 않는 추적 안 된 파일. */
	public Set<String> untracked() {
		String out = Processes.capture(this.dir, "git", "ls-files", "--others", "--exclude-standard");
		return (out != null) ? new LinkedHashSet<>(out.lines().filter((line) -> !line.isBlank()).toList()) : Set.of();
	}

	/**
	 * 시작 시점의 작업 트리(커밋되지 않은 변경 포함)를 ref 로 남긴다. 누적 patch 의 기준이라 --allow-dirty 로 시작해도 되돌릴 때
	 * 기존 변경이 지워지지 않는다. ref 로 잡아 두어 git gc 에 지워지지 않는다.
	 * @return 시작 상태 커밋 id. 만들지 못하면 null
	 */
	public @Nullable String pinStart(String ref, String tree) {
		String commit = Processes.capture(this.dir, "git", "-c", "user.name=spring-boot-migrator", "-c",
				"user.email=spring-boot-migrator@localhost", "commit-tree", tree, "-p", "HEAD", "-m",
				"spring-boot-migrator start");
		if (commit == null || commit.isBlank() || !run("git", "update-ref", ref, commit.trim())) {
			return null;
		}
		return commit.trim();
	}

	public void deleteRef(String ref) {
		run("git", "update-ref", "-d", ref);
	}

	/** 줄바꿈/공백 차이(core.autocrlf 등)로 실패하면 공백을 무시하고 한 번 더 시도한다. */
	public boolean applyReverse(Path patch) {
		return run("git", "apply", "-R", "--binary", patch.toString())
				|| run("git", "apply", "-R", "--binary", "--ignore-whitespace", patch.toString());
	}

	/**
	 * 현재 stage patch 를 되돌리고 이전 stage patch 를 적용한다. 중간에 실패하면 시작 전 작업 트리로 복원한다.
	 * @param previous null 이면 되돌리기만 한다
	 * @return 이전 stage 상태가 되었으면 true, 아니면 작업 트리는 호출 전 그대로다
	 */
	public boolean rollback(Path current, @Nullable Path previous, Collection<String> created, Path tempIndex) {
		String backup = snapshotTree(created, tempIndex);
		if (backup == null || !applyReverse(current)) {
			return false;
		}
		if (previous == null || apply(previous)) {
			return true;
		}
		if (!run("git", "restore", "--source=" + backup, "--worktree", "--", ".")) {
			throw new IllegalStateException(
					"되돌리기에 실패해서 작업 트리를 복원하려 했지만 복원도 실패했어요. 되돌리기 전 작업 트리는 tree " + backup + " 에 있어요");
		}
		return false;
	}

	public boolean apply(Path patch) {
		return run("git", "apply", "--binary", patch.toString())
				|| run("git", "apply", "--binary", "--ignore-whitespace", patch.toString());
	}

	/**
	 * 추적 중인 파일의 변경 + created 만 커밋한다 (git add -A 를 쓰지 않는다). 담을 변경이 없으면 커밋하지 않는다 (바꾼 것이 없는
	 * stage , 사용자가 이미 직접 커밋한 재개).
	 * @param errors git 의 표준 에러를 붙일 파일 (hook 이 실패한 이유 등). null 이면 버린다
	 */
	public CommitResult commit(Collection<String> created, String subject, String body, @Nullable Path errors) {
		if (!addToIndex(created)) {
			return CommitResult.FAILED;
		}
		if (run("git", "diff", "--cached", "--quiet")) {
			return CommitResult.NOTHING;
		}
		return Processes.run(this.dir, null, errors, List.of("git", "commit", "-q", "-m", subject, "-m", body),
				Map.of()) ? CommitResult.COMMITTED : CommitResult.FAILED;
	}

	public enum CommitResult {

		COMMITTED, NOTHING, FAILED

	}

	private boolean addToIndex(Collection<String> created) {
		if (!run("git", "add", "-u", "--", ".")) {
			return false;
		}
		List<String> existing = new ArrayList<>();
		for (String file : created) {
			if (Files.exists(this.dir.resolve(file))) {
				existing.add(file);
			}
		}
		if (existing.isEmpty()) {
			return true;
		}
		List<String> add = new ArrayList<>(List.of("git", "add", "--"));
		add.addAll(existing);
		return Processes.run(this.dir, null, add);
	}

	/**
	 * 작업 트리(추적 중인 파일과 created)를 tree 객체로 만든다. stage 전후 tree 를 비교하면 그 stage 의 diff 가 나온다.
	 */
	public @Nullable String snapshotTree(Collection<String> created, Path tempIndex) {
		Map<String, String> env = Map.of("GIT_INDEX_FILE", tempIndex.toString());
		try {
			Files.deleteIfExists(tempIndex);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (!Processes.run(this.dir, null, List.of("git", "read-tree", "HEAD"), env)
				|| !Processes.run(this.dir, null, List.of("git", "add", "-u", "--", "."), env)) {
			return null;
		}
		List<String> existing = new ArrayList<>();
		for (String file : created) {
			if (Files.exists(this.dir.resolve(file))) {
				existing.add(file);
			}
		}
		if (!existing.isEmpty()) {
			List<String> add = new ArrayList<>(List.of("git", "add", "--"));
			add.addAll(existing);
			Processes.run(this.dir, null, add, env);
		}
		String tree = Processes.capture(this.dir, env, "git", "write-tree");
		return (tree != null) ? tree.trim() : null;
	}

	/** 두 tree 사이의 diff 를 patch 로 남긴다. */
	public boolean diffTrees(String from, String to, Path patch) {
		return Processes.run(this.dir, patch, List.of("git", "diff", "--binary", from, to));
	}

	/** HEAD 를 기준으로 분리된 worktree 를 만든다 (target 은 아직 없는 경로). */
	public boolean addWorktree(Path target) {
		return run("git", "worktree", "add", "--detach", "-q", target.toString(), "HEAD");
	}

	/**
	 * @return 지웠으면 true. 실패하면 worktree 가 남아 git worktree list 에 보인다
	 */
	public boolean removeWorktree(Path target) {
		boolean removed = run("git", "worktree", "remove", "--force", target.toString());
		return run("git", "worktree", "prune") && removed;
	}

	/**
	 * 임시 worktree 전용. 빌드 산출물을 뺀 모든 변경(새 파일 포함)을 커밋하고 커밋 id 를 돌려준다. 사용자 저장소에서는 쓰지 않는다.
	 */
	public @Nullable String commitAll(String message) {
		// 무시된 경로(build 등)를 pathspec 에 적으면 git add 가 실패해서, 추적 중인 파일과 새 파일을 나눠 담는다
		List<String> created = untracked().stream()
			.filter((file) -> !file.startsWith("build/") && !file.contains("/build/"))
			.toList();
		if (!addToIndex(created)) {
			return null;
		}
		if (!run("git", "-c", "user.name=spring-boot-migrator", "-c", "user.email=spring-boot-migrator@localhost",
				"commit", "-q", "--allow-empty", "--no-verify", "-m", message)) {
			return null;
		}
		return head();
	}

	public String lastCommit() {
		String out = Processes.capture(this.dir, "git", "log", "-1", "--format=%h %s");
		return (out != null) ? out.trim() : "";
	}

	public String recentCommits(int count) {
		String out = Processes.capture(this.dir, "git", "log", "--oneline", "-" + count);
		return (out != null) ? out.trim().replace('\n', ';') : "";
	}

	private boolean run(String... command) {
		return Processes.run(this.dir, null, List.of(command));
	}
}
