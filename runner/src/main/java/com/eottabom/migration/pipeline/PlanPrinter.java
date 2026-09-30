package com.eottabom.migration.pipeline;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.plan.MigrationPlan;
import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.recipe.ProjectRecipes;

/**
 * migrationPlan. 실행할 stage 와 걸릴 수 있는 체크리스트를 보여준다. 대상 프로젝트의 Gradle 을 띄우지 않는다.
 */
record PlanPrinter(RunnerConsole console, Guides guides) {

	void print(ProjectState project, MigrationPlan plan, ProjectRecipes projectRecipes) {
		this.console.step("프로젝트 : " + project.dir());
		this.console.line("   현재     : Boot {} / Gradle {} / Java {}", project.bootVersion(),
				RunnerConsole.orQ(project.gradleVersion()), RunnerConsole.orQ(project.javaVersion()));
		this.console.line("   목표     : Boot {} / Java {}", plan.targetBoot(),
				(plan.targetJava() == null) ? "유지" : plan.targetJava());
		this.console.targetLine(plan);
		this.console.notes(plan);
		if (plan.isEmpty()) {
			this.console.line("   stage    : 없음 (이미 목표 이상)");
			return;
		}
		this.console.line("   stage    :");
		for (Stage stage : plan.stages()) {
			this.console.line("     {}  {}{}", String.format("%-11s", stage.name()), stage.recipeNames(),
					RunnerConsole.projectRecipeSuffix(projectRecipes, stage));
		}
		this.console.projectRecipes(project.dir(), projectRecipes);
		this.console.checklistPreview(plan.stages(), this.guides);
	}

}
