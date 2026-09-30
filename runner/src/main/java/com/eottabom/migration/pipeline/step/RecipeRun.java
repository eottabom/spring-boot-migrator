package com.eottabom.migration.pipeline.step;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import com.eottabom.migration.MigrationException;
import com.eottabom.migration.git.Git;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.gradle.ProjectGradle.RewriteTask;
import com.eottabom.migration.recipe.AssembledRecipe.Assembled;
import org.jspecify.annotations.Nullable;

/**
 * 조립한 레시피로 rewriteRun 을 한 번 돌린다. stage 레시피(Rewrite)와 deprecated API 대체(Deprecation)가 같이
 * 쓴다.
 */
public final class RecipeRun {

	private final ProjectGradle gradle;

	private final @Nullable Git git;

	/**
	 * @param git git 저장소가 아니면 null
	 */
	public RecipeRun(ProjectGradle gradle, @Nullable Git git) {
		this.gradle = gradle;
		this.git = git;
	}

	/**
	 * 레시피를 돌리기 전의 작업 트리.
	 * @param createdFiles 지금까지 레시피가 만든 파일 (추적 안 된 파일 중 트리에 넣을 것)
	 * @return tree 객체. git 저장소가 아니면 null
	 */
	public @Nullable String snapshotTree(Collection<String> createdFiles, Path tempIndex) {
		return (this.git != null) ? this.git.snapshotTree(createdFiles, tempIndex) : null;
	}

	/**
	 * @return rewriteRun 이 새로 만든 파일. 이후 빌드가 만든 파일은 patch 와 커밋에 넣지 않으려고 여기서 가려 둔다. git
	 * 저장소가 아니면 빈 집합
	 */
	public Set<String> run(Assembled assembled, Path log) {
		Set<String> untrackedBefore = (this.git != null) ? this.git.untracked() : Set.of();
		if (!this.gradle.rewrite(log, RewriteTask.RUN, assembled.name(), assembled.file())) {
			throw new MigrationException("rewriteRun 실패 → " + log);
		}
		if (this.git == null) {
			return Set.of();
		}
		Set<String> created = this.git.untracked();
		created.removeAll(untrackedBefore);
		return created;
	}

}
