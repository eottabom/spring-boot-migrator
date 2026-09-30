package com.eottabom.migration.pipeline;

import java.nio.file.Path;
import java.util.List;

import com.eottabom.migration.git.Git;
import com.eottabom.migration.git.WorkingTree;
import com.eottabom.migration.recipe.AssembledRecipe;
import com.eottabom.migration.workspace.MigrationWorkspace;

/** 러너가 대상 프로젝트 안에 만드는 것. 사용자의 변경이 아니므로 작업 트리 검사에서 뺀다. */
final class RunnerOutputs {

	static final List<String> PATHS = List.of(MigrationWorkspace.DIR_NAME, AssembledRecipe.RELATIVE_PATH);

	private RunnerOutputs() {
	}

	/** 러너가 만든 것을 뺀 작업 트리 상태. scan 이나 verify 를 먼저 돌리면 git exclude 를 쓰기 전에 결과 디렉토리가 생긴다 */
	static WorkingTree workingTree(Path projectDir) {
		return new Git(projectDir).workingTree(PATHS);
	}

}
