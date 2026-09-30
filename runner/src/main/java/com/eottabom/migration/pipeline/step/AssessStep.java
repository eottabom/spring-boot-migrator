package com.eottabom.migration.pipeline.step;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.recipe.ProjectRecipes.ProjectRecipe;
import com.eottabom.migration.result.Outcome;
import com.eottabom.migration.result.ReportedChecklistItem;
import com.eottabom.migration.result.StageResult;
import com.eottabom.migration.version.ResolvedVersions;
import com.eottabom.migration.workspace.RunFiles;
import com.eottabom.migration.workspace.StageFiles;

/**
 * 게이트 결과와 로그로 stage 결과를 모은다. 체크리스트는 stage 전후 resolve 된 버전으로 고르고, 실패 힌트는 stage 가이드와 공통
 * 가이드에서 가져온다. 결과는 이 모델을 그리기만 한다.
 */
public final class AssessStep {

	private final Guides guides;

	private final Path projectDir;

	private final RunFiles start;

	private final ProjectRecipes projectRecipes;

	/**
	 * @param start 시작할 때 모은 파일 (detect 결과)
	 */
	public AssessStep(Guides guides, Path projectDir, RunFiles start, ProjectRecipes projectRecipes) {
		this.guides = guides;
		this.projectDir = projectDir;
		this.start = start;
		this.projectRecipes = projectRecipes;
	}

	/**
	 * @param versionsBefore stage 전 resolve 된 버전
	 * @param versionsAfter stage 후 resolve 된 버전
	 * @param baselineFailedTests 원본에서도 실패하던 테스트 (결과에 기존 실패로 표시)
	 */
	public StageResult assess(Stage stage, StageFiles files, ResolvedVersions versionsBefore,
			ResolvedVersions versionsAfter, GateOutcome gate, List<String> deprecationFixes,
			Set<String> baselineFailedTests) {
		List<ReportedChecklistItem> checklist = this.guides.checklist(stage.covers(), versionsBefore, versionsAfter)
			.stream()
			.map(ReportedChecklistItem::of)
			.toList();
		Set<String> projectRecipeNames = Set
			.copyOf(this.projectRecipes.recipes().stream().map(ProjectRecipe::name).toList());
		// 원본에서도 실패하던 태스크만 실패했으면 기존 문제로 표시한다
		boolean buildFailureExisting = gate.build() == Outcome.FAILED && !gate.hasNewBuildFailure();
		return StageResult.assess(new StageResult.Input(stage.id(), stage.covers(), this.projectDir,
				new StageResult.Logs(files.compileLog(), files.rewriteLog(), this.start.detectPatch()), versionsBefore,
				versionsAfter,
				new StageResult.Gates(gate.compile(), gate.build(), buildFailureExisting, gate.flakyTests(),
						gate.testResultFiles(), gate.unreadableResults()),
				new StageResult.Guidance(checklist, this.guides.stage(stage.id()).source(),
						this.guides.failureHints(stage.covers())),
				projectRecipeNames, baselineFailedTests, deprecationFixes));
	}

}
