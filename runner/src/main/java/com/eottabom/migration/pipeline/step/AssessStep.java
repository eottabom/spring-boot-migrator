package com.eottabom.migration.pipeline.step;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.recipe.ProjectRecipes.ProjectRecipe;
import com.eottabom.migration.result.DependencyChanges;
import com.eottabom.migration.result.Outcome;
import com.eottabom.migration.result.ReportedChecklistItem;
import com.eottabom.migration.result.StageResult;
import com.eottabom.migration.workspace.RunFiles;
import com.eottabom.migration.workspace.StageFiles;

/**
 * 게이트 결과와 로그로 stage 결과를 모은다. 체크리스트는 stage 전후 resolve 된 버전으로 고르고, 실패 힌트는 stage 가이드와 공통
 * 가이드에서 가져온다. 결과는 이 모델을 그리기만 한다.
 */
public record AssessStep(Guides guides) {

	/**
	 * @param covers stage 가 다룬 stage 이름
	 * @param previousVersions stage 전 resolve 된 버전
	 * @param baselineFailedTests 원본에서도 실패하던 테스트 (결과에 기존 실패로 표시)
	 */
	public StageResult assess(String stageName, List<String> covers, Path projectDir, StageFiles files, RunFiles start,
			Path previousVersions, GateOutcome gate, List<String> deprecationFixes, ProjectRecipes projectRecipes,
			Set<String> baselineFailedTests) {
		// 원본에서도 실패하던 태스크만 실패했으면 기존 문제로 표시한다
		boolean buildFailureExisting = gate.build() == Outcome.FAILED && !gate.buildBlocking();
		Set<String> projectRecipeNames = Set
			.copyOf(projectRecipes.recipes().stream().map(ProjectRecipe::name).toList());
		return StageResult.assess(new StageResult.Input(stageName, covers, projectDir, files.compileLog(),
				files.rewriteLog(), start.detectPatch(), previousVersions, files.versions(), gate.compile(),
				gate.build(), buildFailureExisting, checklist(covers, previousVersions, files.versions()),
				this.guides.stage(stageName).source(), this.guides.failureHints(covers), projectRecipeNames,
				baselineFailedTests, gate.flakyTests(), gate.testResults(), gate.unreadableResults(),
				deprecationFixes));
	}

	private List<ReportedChecklistItem> checklist(List<String> covers, Path beforeVersions, Path afterVersions) {
		return this.guides
			.checklist(covers, DependencyChanges.readVersions(beforeVersions),
					DependencyChanges.readVersions(afterVersions))
			.stream()
			.map(ReportedChecklistItem::of)
			.toList();
	}

}
