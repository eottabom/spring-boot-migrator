package com.eottabom.migration.pipeline.step;

import java.util.Collection;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.workspace.Git;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.StageFiles;
import org.gradle.api.GradleException;

/**
 * stage 커밋. 추적 중인 파일의 변경과 레시피가 만든 파일만 담는다.
 */
public record CommitStep(RunnerConsole console) {

	/** 커밋하지 못하면 멈춘다. 다음 stage 로 가면 두 stage 의 변경이 한 커밋에 섞인다. 담을 변경이 없으면 그냥 넘어간다 */
	public void commit(Git git, Collection<String> createdFiles, StageFiles files, StageTag tag, String detail) {
		String stageName = tag.stage().name();
		Git.CommitResult result = git.commit(createdFiles, "chore: Spring Boot " + stageName + " 마이그레이션 " + detail,
				"- 결과는 " + MigrationWorkspace.DIR_NAME + "/" + tag + "/result.md", files.commitLog());
		switch (result) {
			case COMMITTED -> this.console.line("   commit: {}", git.lastCommit());
			case NOTHING -> this.console.line("   commit: 담을 변경이 없어 건너뛰어요");
			case FAILED -> throw new GradleException("[" + stageName + "] commit 실패 (pre-commit hook 등) → "
					+ files.commitLog() + "\n   직접 커밋하고 같은 명령을 다시 실행하면 다음 stage 부터 이어서 해요");
		}
	}

}
