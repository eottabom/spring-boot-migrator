package com.eottabom.migration.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;

/**
 * migrationScan, migrationPlan, migrationRun, migrationVerify, migrationHelp 를 등록한다. 레시피
 * jar 는 recipes 모듈이 만들고 대상 프로젝트에는 init script 로 붙인다.
 */
public class MigrationPlugin implements Plugin<Project> {

	private static final String GROUP = "migration";

	@Override
	public void apply(Project project) {
		Directory root = project.getLayout().getProjectDirectory();
		// recipes 모듈이 루트보다 나중에 설정되어 경로로 건다
		String recipeLibs = ":recipes:recipeLibs";

		project.getTasks().withType(MigrationTask.class).configureEach((task) -> {
			task.setGroup(GROUP);
			task.doNotTrackState("대상 프로젝트는 이 빌드 밖에 있어요");
			// 상대 경로 --project, --config 는 이 저장소가 아니라 명령을 실행한 위치 기준 (./gradlew -p 로 실행해도
			// 같다)
			task.getInvocationDir()
				.convention(project.getLayout()
					.dir(project.provider(() -> project.getGradle().getStartParameter().getCurrentDir())));
			task.getRewriteInitScript().convention(root.file("init/rewrite.init.gradle"));
			task.getVerifyInitScript().convention(root.file("init/verify.init.gradle"));
			task.getRecipeLibs().convention(root.dir("recipes/build/recipe-libs"));
			task.getGuidesDir().convention(root.dir("guides"));
			task.getSchemaDir().convention(root.dir("schema"));
		});

		project.getTasks().register("migrationHelp", MigrationHelpTask.class, (task) -> {
			task.setGroup(GROUP);
			task.setDescription("migration 태스크와 옵션 안내");
		});
		project.getTasks().register("migrationScan", MigrationScanTask.class, (task) -> {
			task.setDescription("대상 프로젝트의 현재 버전, 의존성, detect 레시피가 찾은 위치를 보여 줘요 (--project)");
			task.dependsOn(recipeLibs);
		});
		project.getTasks()
			.register("migrationPlan", MigrationPlanTask.class,
					(task) -> task.setDescription("대상 프로젝트에서 실행할 stage 와 레시피를 보여 줘요 (--project, --boot, --java)"));
		project.getTasks().register("migrationRun", MigrationRunTask.class, (task) -> {
			task.setDescription("대상 프로젝트를 목표 Spring Boot 까지 stage 별로 마이그레이션해요 (--project, --boot, ...)");
			task.dependsOn(recipeLibs);
		});
		project.getTasks()
			.register("migrationVerify", MigrationVerifyTask.class,
					(task) -> task.setDescription("대상 프로젝트의 컴파일과 테스트를 검증해요 (--project, --gate)"));
	}

}
