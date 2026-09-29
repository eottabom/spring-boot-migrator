package com.eottabom.migration.console;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.eottabom.migration.guide.BootRequirements;
import com.eottabom.migration.guide.ChecklistItem;
import com.eottabom.migration.guide.ChecklistItem.Fix;
import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.plan.MigrationPlan;
import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.recipe.ProjectRecipes.Order;
import com.eottabom.migration.recipe.ProjectRecipes.ProjectRecipe;
import org.gradle.api.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * 러너의 콘솔 출력. stage 제목은 {@code >>}, 오류는 {@code !!} 로 시작한다.
 */
public record RunnerConsole(Logger logger) {

	public void project(ProjectState project, @Nullable String javaHome) {
		this.step("프로젝트 : " + project.dir());
		this.line("   Boot     : {}", orQ(project.bootVersion()));
		this.line("   Gradle   : {}", orQ(project.gradleVersion()));
		this.line("   Java     : {}", orQ(project.javaVersion()));
		this.line("   JAVA_HOME: {}", orDefault(javaHome));
		this.line("   git      : {}", !project.git() ? "아님" : project.dirty() ? "커밋되지 않은 변경 있음" : "깨끗함");
	}

	public void projectRecipes(Path projectDir, ProjectRecipes projectRecipes) {
		if (projectRecipes.files().isEmpty()) {
			return;
		}
		this.step("프로젝트 레시피 ("
				+ String.join(", ",
						projectRecipes.files().stream().map((file) -> projectDir.relativize(file).toString()).toList())
				+ ")");
		if (projectRecipes.recipes().isEmpty()) {
			this.line("   migration-stage 태그가 붙은 레시피가 없어요 (태그가 없는 레시피는 다른 레시피가 참조할 때만 쓰여요)");
		}
		for (ProjectRecipe recipe : projectRecipes.recipes()) {
			this.line("   {}  stage {} / {}", recipe.name(), recipe.stages(),
					(recipe.order() == Order.BEFORE) ? "before" : "after");
		}
	}

	public void targetLine(MigrationPlan plan) {
		BootRequirements requirements = plan.targetRequirements();
		this.line(
				"   호환성   : Java {} ~ {} / Gradle {} / Spring Framework {} / Spring Cloud {} ({}+) / Spring Cloud AWS {}",
				requirements.java().min(), requirements.java().max(), requirements.gradleRange(),
				requirements.framework(), requirements.springCloud().train(), requirements.springCloud().since(),
				requirements.springCloudAws());
	}

	public void notes(MigrationPlan plan) {
		plan.notes().forEach((note) -> this.line("   참고     : {}", note));
	}

	/** stage 별로 걸릴 수 있는 체크리스트 항목. 의존성 조건은 실행 때 판단한다 */
	public void checklistPreview(List<Stage> stages, Guides guides) {
		this.step("체크리스트 미리보기 (guides/, 의존성 조건은 실행 때 판단)");
		Map<Fix, Integer> total = new EnumMap<>(Fix.class);
		for (Stage stage : stages) {
			List<ChecklistItem> items = stage.covers()
				.stream()
				.flatMap((name) -> guides.stage(name).checklist().stream())
				.toList();
			if (items.isEmpty()) {
				continue;
			}
			this.line("   [{}]", stage.name());
			for (Fix fix : List.of(Fix.MANUAL, Fix.ASSISTED, Fix.AUTO)) {
				for (ChecklistItem item : items) {
					if (item.fix() == fix) {
						total.merge(fix, 1, Integer::sum);
						String when = (item.when() == null) ? ""
								: "  (" + String.join(", ", item.when().dependencies()) + " 사용 시)";
						this.line("     {}  {}{}", String.format("%-8s", fix.name().toLowerCase(Locale.ROOT)),
								item.title(), when);
					}
				}
			}
		}
		this.line("   합계: manual {} / assisted {} / auto {}", total.getOrDefault(Fix.MANUAL, 0),
				total.getOrDefault(Fix.ASSISTED, 0), total.getOrDefault(Fix.AUTO, 0));
	}

	public static String projectRecipeSuffix(ProjectRecipes projectRecipes, Stage stage) {
		List<String> before = projectRecipes.names(stage.covers(), Order.BEFORE);
		List<String> after = projectRecipes.names(stage.covers(), Order.AFTER);
		if (before.isEmpty() && after.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder("  + 프로젝트");
		if (!before.isEmpty()) {
			out.append(" before ").append(before);
		}
		if (!after.isEmpty()) {
			out.append(" after ").append(after);
		}
		return out.toString();
	}

	public void step(String message) {
		this.logger.lifecycle("");
		this.logger.lifecycle(">> {}", message);
	}

	public void line(String format, @Nullable Object... args) {
		this.logger.lifecycle(format, args);
	}

	public void error(String message) {
		this.logger.error("!! {}", message);
	}

	public static String orQ(@Nullable Object value) {
		return (value != null) ? value.toString() : "?";
	}

	public static String orDefault(@Nullable String value) {
		return (value != null) ? value : "default";
	}

}
