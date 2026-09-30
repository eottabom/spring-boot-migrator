package com.eottabom.migration.pipeline.step;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.recipe.AssembledRecipe;
import com.eottabom.migration.recipe.AssembledRecipe.Assembled;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.StageFiles;
import org.jspecify.annotations.Nullable;

/**
 * stage 레시피와 프로젝트 레시피를 조립해 rewriteRun 을 돌린다. git 저장소면 stage 전 작업 트리와 레시피가 만든 파일을 기록한다.
 *
 * @param projectDir 대상 프로젝트
 */
public record RewriteStep(RecipeRun recipeRun, RunnerConsole console, Path projectDir) {

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
		String treeBefore = (this.recipeRun.git() != null) ? this.recipeRun.git().snapshotTree(createdFiles, tempIndex)
				: null;
		return new RewriteOutcome(treeBefore, this.recipeRun.run(assembled, files.rewriteLog()));
	}

	/**
	 * @param treeBefore stage 전 작업 트리 (git 저장소가 아니면 null)
	 * @param createdFiles rewriteRun 이 새로 만든 파일
	 */
	public record RewriteOutcome(@Nullable String treeBefore, Set<String> createdFiles) {
	}

}
