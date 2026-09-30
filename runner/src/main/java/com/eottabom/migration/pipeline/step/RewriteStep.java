package com.eottabom.migration.pipeline.step;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.recipe.AssembledRecipe;
import com.eottabom.migration.recipe.AssembledRecipe.Assembled;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.workspace.Git;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.StageFiles;
import org.gradle.api.GradleException;
import org.jspecify.annotations.Nullable;

/**
 * stage 레시피와 프로젝트 레시피를 조립해 rewriteRun 을 돌린다. git 저장소면 stage 전 작업 트리와 레시피가 만든 파일을 기록한다.
 *
 * @param projectDir 대상 프로젝트 (preview 면 임시 worktree)
 * @param rewriteInit init/rewrite.init.gradle
 * @param recipeLibs 레시피 jar 와 의존 jar
 * @param git git 저장소가 아니면 null
 */
public record RewriteStep(ProjectGradle gradle, RunnerConsole console, Path projectDir, Path rewriteInit,
		Path recipeLibs, @Nullable Git git) {

	/**
	 * @param createdFiles 지금까지 레시피가 만든 파일 (stage 전 작업 트리에 넣는다)
	 */
	public RewriteOutcome run(Stage stage, StageTag tag, StageFiles files, ProjectRecipes projectRecipes,
			Collection<String> createdFiles, Path tempIndex) {
		Assembled assembled = AssembledRecipe.write(this.projectDir, String.valueOf(this.projectDir.getFileName()),
				stage, tag, projectRecipes);
		MigrationWorkspace.copyOrEmpty(assembled.file(), files.assembledRecipe());
		this.console.heading("[" + stage.name() + "] rewriteRun " + stage.recipeNames()
				+ RunnerConsole.projectRecipeSuffix(projectRecipes, stage));
		Set<String> untrackedBefore = (this.git != null) ? this.git.untracked() : Set.of();
		String treeBefore = (this.git != null) ? this.git.snapshotTree(createdFiles, tempIndex) : null;
		if (!this.gradle.rewrite(files.rewriteLog(), "rewriteRun", assembled.name(), this.rewriteInit, this.recipeLibs,
				assembled.file())) {
			throw new GradleException("rewriteRun 실패 → " + files.rewriteLog());
		}
		return new RewriteOutcome(treeBefore, created(untrackedBefore));
	}

	/** 레시피 목록만 한 번 더 돌린다 (deprecated API 대체) */
	public Set<String> runRecipes(String name, List<String> recipes, Path log) {
		Set<String> untrackedBefore = (this.git != null) ? this.git.untracked() : Set.of();
		Assembled assembled = AssembledRecipe.write(this.projectDir, name, recipes);
		if (!this.gradle.rewrite(log, "rewriteRun", assembled.name(), this.rewriteInit, this.recipeLibs,
				assembled.file())) {
			throw new GradleException("rewriteRun 실패 → " + log);
		}
		return created(untrackedBefore);
	}

	/** rewriteRun 이 만든 파일만 기록한다. 이후 빌드가 만든 파일은 patch 와 커밋에 넣지 않는다 */
	private Set<String> created(Set<String> untrackedBefore) {
		if (this.git == null) {
			return Set.of();
		}
		Set<String> created = this.git.untracked();
		created.removeAll(untrackedBefore);
		return created;
	}

	/**
	 * @param treeBefore stage 전 작업 트리 (git 저장소가 아니면 null)
	 * @param createdFiles rewriteRun 이 새로 만든 파일
	 */
	public record RewriteOutcome(@Nullable String treeBefore, Set<String> createdFiles) {
	}

}
