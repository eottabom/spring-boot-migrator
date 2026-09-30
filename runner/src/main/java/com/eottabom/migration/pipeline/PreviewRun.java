package com.eottabom.migration.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.misc.TextFiles;
import com.eottabom.migration.pipeline.MigrationRunner.RunnerPaths;
import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.project.ProjectInspector;
import com.eottabom.migration.recipe.AssembledRecipe;
import com.eottabom.migration.recipe.AssembledRecipe.Assembled;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.workspace.Git;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.StageFiles;
import org.gradle.api.GradleException;
import org.jspecify.annotations.Nullable;

/**
 * --mode=preview. 모든 stage 의 변경을 미리 본다. 앞 stage 를 적용해야 다음 stage 를 알 수 있어서 임시 git worktree
 * 에 차례로 적용하고 stage 별 patch 만 남긴다. 대상 프로젝트의 작업 트리는 바뀌지 않는다. git 저장소가 아니면 첫 stage 만
 * rewriteDryRun 으로 본다.
 */
record PreviewRun(RunnerPaths paths, ProjectGradle.Factory gradleFactory, ProjectInspector inspector,
		RunnerConsole console) {

	/**
	 * @param order 첫 stage 번호 - 1 (지난 기록 뒤에 이어서 붙인다)
	 */
	void run(Path projectDir, MigrationWorkspace ws, List<Stage> stages, int order, ProjectRecipes projectRecipes,
			@Nullable String javaHome) {
		String projectName = projectDir.getFileName().toString();
		Git git = new Git(projectDir);
		Path tempDir = createTempDir();
		Path worktree = tempDir.resolve(projectName);
		if (!git.addWorktree(worktree)) {
			throw new GradleException("preview 용 임시 worktree 를 만들지 못했어요 → " + worktree);
		}
		try {
			ProjectGradle gradle = this.gradleFactory.create(worktree, javaHome);
			Git worktreeGit = new Git(worktree);
			String previous = Objects.requireNonNull(worktreeGit.head(), "미리보기 worktree 의 HEAD");
			int number = order;
			for (Stage stage : stages) {
				number++;
				String tag = stage.tag(number);
				StageFiles files = ws.stage(tag);
				Assembled assembled = AssembledRecipe.write(worktree, projectName, stage, tag, projectRecipes);
				this.console.step("[" + stage.name() + "] " + stage.recipeNames()
						+ RunnerConsole.projectRecipeSuffix(projectRecipes, stage) + " (preview)");
				if (!gradle.rewrite(files.rewriteLog(), "rewriteRun", assembled.name(), this.paths.rewriteInit(),
						this.paths.recipeLibs(), assembled.file())) {
					throw new GradleException("preview 실패 → " + files.rewriteLog());
				}
				String current = worktreeGit.commitAll("preview " + stage.name());
				Path patch = files.previewPatch();
				if (current == null || !worktreeGit.diffTrees(previous, current, patch)) {
					throw new GradleException("preview patch 를 만들지 못했어요 → " + patch);
				}
				previous = current;
				this.console.line("   Boot {}, {} files → {}", RunnerConsole.orQ(this.inspector.bootVersion(worktree)),
						TextFiles.countMatches(patch, "^diff --git"), patch);
			}
		}
		finally {
			removeWorktree(git, ws, projectName, worktree);
			deleteQuietly(tempDir);
		}
		this.console.line("   (컴파일과 테스트는 하지 않아요. 커밋되지 않은 변경은 preview 에 들어가지 않아요)");
	}

	/** git 저장소가 아니면 임시 worktree 를 쓸 수 없어 첫 stage 만 rewriteDryRun 으로 본다 */
	void firstStage(Path projectDir, MigrationWorkspace ws, Stage stage, String tag, ProjectRecipes projectRecipes,
			ProjectGradle gradle) {
		StageFiles files = ws.stage(tag);
		Assembled assembled = AssembledRecipe.write(projectDir, String.valueOf(projectDir.getFileName()), stage, tag,
				projectRecipes);
		MigrationWorkspace.copyOrEmpty(assembled.file(), files.assembledRecipe());
		this.console.step("[" + stage.name() + "] " + stage.recipeNames() + " (preview)");
		if (!gradle.rewrite(files.rewriteLog(), "rewriteDryRun", assembled.name(), this.paths.rewriteInit(),
				this.paths.recipeLibs(), assembled.file())) {
			throw new GradleException("preview 실패 → " + files.rewriteLog());
		}
		if (MigrationWorkspace.copyOrEmpty(ProjectScanner.rewritePatch(projectDir), files.previewPatch())) {
			this.console.line("   {} files → {}", TextFiles.countMatches(files.previewPatch(), "^diff --git"),
					files.previewPatch());
		}
		else {
			this.console.line("   변경 없음");
		}
		this.console.line("   (git 저장소가 아니라 첫 stage 만 보여 줘요. git 저장소면 모든 stage 를 임시 worktree 에서 미리 봐요)");
	}

	/** 지우지 못해도 미리보기 결과는 그대로라 멈추지 않고, 남은 경로를 알린다 */
	private void removeWorktree(Git git, MigrationWorkspace ws, String projectName, Path worktree) {
		if (!git.removeWorktree(worktree)) {
			String message = "임시 worktree 를 지우지 못했어요. git worktree list 로 확인하고 지워 주세요 → " + worktree;
			this.console.error(message);
			ws.appendHistory(projectName, "- " + message + "\n");
		}
	}

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("spring-boot-migrator-preview");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void deleteQuietly(Path dir) {
		try {
			Files.deleteIfExists(dir);
		}
		catch (IOException ex) {
			// worktree remove 가 남긴 빈 디렉토리는 임시 디렉토리라 그대로 둬도 된다
		}
	}

}
