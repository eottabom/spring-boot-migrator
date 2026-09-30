package com.eottabom.migration.pipeline.step;

import java.nio.file.Path;
import java.util.Collection;

import com.eottabom.migration.MigrationException;
import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.git.Git;
import com.eottabom.migration.workspace.StageFiles;
import org.jspecify.annotations.Nullable;

/**
 * stage 의 patch. 이 stage 에서만 바뀐 diff(stage.patch, result.html 의 변경 보기)와 시작 시점 대비 누적
 * patch(cumulative.patch, 재개와 되돌리기의 기준). stage 를 처음 돌 때와 재개해서 통과할 때 같은 방법으로 만든다.
 */
public record StagePatches(RunnerConsole console, Git git) {

	/**
	 * @param createdFiles 레시피가 만든 파일 (추적 안 된 파일 중 patch 에 넣을 것)
	 * @param treeBefore stage 전 작업 트리. 모르면 stage diff 는 건너뛴다
	 * @param start 누적 patch 의 기준
	 */
	public void write(StageFiles files, Collection<String> createdFiles, Path tempIndex, @Nullable String treeBefore,
			String start) {
		String treeAfter = this.git.snapshotTree(createdFiles, tempIndex);
		// 누적 patch 가 없거나 틀리면 재개와 되돌리기가 사용자 변경을 잃는다. 결과와 달리 실패하면 멈춘다
		if (treeAfter == null || !this.git.diffTrees(start, treeAfter, files.cumulativePatch())) {
			throw new MigrationException("누적 patch 를 만들지 못했어요: " + files.cumulativePatch());
		}
		if (treeBefore == null || !this.git.diffTrees(treeBefore, treeAfter, files.stagePatch())) {
			this.console.error("이 stage 의 diff 를 만들지 못해 결과에서 변경 보기가 빠져요: " + files.stagePatch());
		}
	}

}
