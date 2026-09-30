package com.eottabom.migration.plan;

import java.nio.file.Path;
import java.util.stream.Stream;

import com.eottabom.migration.config.JavaTarget;
import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.config.Mode;
import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.stage.StageId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationPlannerTests {

	private final MigrationPlanner planner = new MigrationPlanner(
			Guides.load(Path.of("../guides"), Path.of("../schema")));

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("planStageScenarios")
	void plansStages(String scenario, ProjectState project, MigrationConfig request, String expectedStages) {
		MigrationPlan plan = this.planner.plan(project, request);
		assertThat(plan.stageNames()).isEqualTo(expectedStages);
	}

	// @formatter:off
	static Stream<Arguments> planStageScenarios() {
		return Stream.of(
			Arguments.of(
				"Boot 3.2.8 -> 3.5 순차 minor 단계 계획",
				project("3.2.8", "8.8", 17), request("3.5", "keep"),
				"3.3 3.4 3.5"
			),
			Arguments.of(
				"현재 Gradle(8.3)이 부족하면 다음 단계 직전에 Gradle 8.14 업그레이드 삽입",
				project("3.2.8", "8.3", 17), request("3.5", "keep"),
				"3.3 gradle8.14 3.4 3.5"
			),
			Arguments.of(
				"목표 버전 미지정(null) 시 최신 Boot(4.1) 및 필요 시 Gradle 8.14 삽입",
				project("3.5.0", "8.8", 21), request(null, "keep"),
				"gradle8.14 4.0 4.1"
			),
			Arguments.of(
				"Gradle 9.1.0 호환 환경에서 3.4 -> 3.5 단일 단계 계획",
				project("3.4.0", "9.1.0", 21), request("3.5", "keep"),
				"3.5"
			),
			Arguments.of(
				"Boot 2.7.18 -> 3.0 마이그레이션",
				project("2.7.18", "7.4", 11), request("3.0", "keep"),
				"3.0"
			),
			Arguments.of(
				"--java latest 옵션 지정 시 3.4 단계 후 Java 21 업그레이드 추가",
				project("3.3.5", "8.8", 17), request("3.4", "latest"),
				"3.4 java21"
			),
			Arguments.of(
				"Boot 3.5.0 -> 4.1 마이그레이션 시 Java 25 에 필요한 Gradle 9.1 과 함께 올린다",
				project("3.5.0", "8.14.3", 21), request("4.1", "latest"),
				"4.0 4.1 gradle9.1 java25"
			),
			Arguments.of(
				"Gradle(8.4) 업그레이드와 명시적 Java 21 업그레이드가 함께 필요한 경우",
				project("3.3.5", "8.4", 17), request("3.4", "21"),
				"3.4 gradle8.14 java21"
			),
			Arguments.of(
				"이미 목표 버전보다 상위 버전인 경우 빈 단계 계획 반환",
				project("3.5.3", "8.14", 21), request("3.4", "keep"),
				""
			),
			Arguments.of(
				"목표보다 높은 Boot 에는 기본값이어도 Java 단계를 붙이지 않는다",
				project("4.0.1", "8.14", 21), request("3.5", null),
				""
			),
			Arguments.of(
				"--java 를 주지 않으면 목표 Boot 가 지원하는 최신 LTS 까지 올린다",
				project("3.2.8", "8.8", 17), request("3.4", null),
				"3.3 3.4 java21"
			),
			Arguments.of(
				"Boot 3.5 도 지원 범위의 가장 높은 LTS(25) 까지 올리고 Gradle 9.1 을 함께 올린다",
				project("3.4.0", "8.14", 17), request("3.5", null),
				"3.5 gradle9.1 java25"
			),
			Arguments.of(
				"Boot 4.0 기본값은 25, 이미 Gradle 9.1 이면 Gradle stage 를 넣지 않는다",
				project("3.5.0", "9.1.0", 21), request("4.0", null),
				"4.0 java25"
			),
			Arguments.of(
				"3.0 단계가 Gradle 을 7.6.4 로 올리므로 뒤 단계에 Gradle 단계를 넣지 않는다",
				project("2.7.18", "7.4", 11), request("3.5", "keep"),
				"3.0 3.1 3.2 3.3 3.4 3.5"
			),
			Arguments.of(
				"3.0 단계가 Java 를 17 로 올리므로 --java=17 에 Java 단계를 넣지 않는다",
				project("2.7.18", "7.4", 11), request("3.0", "17"),
				"3.0"
			),
			Arguments.of(
				"--boot 에 patch 버전을 주면 minor 로 맞춘다",
				project("3.3.5", "8.8", 17), request("3.4.5", "keep"),
				"3.4"
			)
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("decisionNoteScenarios")
	void explainsJavaAndGradleDecisions(String scenario, ProjectState project, MigrationConfig request,
			String expectedNote) {
		MigrationPlan plan = this.planner.plan(project, request);
		assertThat(plan.notes()).anyMatch((note) -> note.contains(expectedNote));
	}

	// @formatter:off
	static Stream<Arguments> decisionNoteScenarios() {
		return Stream.of(
			Arguments.of(
				"Java 버전이 지원 범위 내에 있어 유지됨을 설명",
				project("3.2.8", "8.8", 17), request("3.5", "keep"),
				"Java 17 는 Boot 3.5 지원 범위(17 ~ 25) 안이라 그대로 둬요"
			),
			Arguments.of(
				"Gradle 9.1.0이 공식 지원 목록에 없음을 안내",
				project("3.4.0", "9.1.0", 21), request("3.5", "keep"),
				"공식 지원 목록"
			),
			Arguments.of(
				"--java=25 를 직접 주면 Boot 3.5 공식 지원 목록 밖의 Gradle 로 올림을 안내",
				project("3.4.0", "8.14", 21), request("3.5", "25"),
				"Gradle 9.1 는 Boot 3.5 공식 지원 목록(7.6.4+ / 8.4+)에 없어요"
			),
			Arguments.of(
				"Java 11에서 17로 자동 상향됨을 설명",
				project("2.7.18", "7.4", 11), request("3.0", "keep"),
				"Java 11 → 17"
			)
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("stageRecipeScenarios")
	void choosesStageRecipes(String scenario, ProjectState project, MigrationConfig request, String expectedStages,
			String expectedRecipes) {
		MigrationPlan plan = this.planner.plan(project, request);

		assertThat(plan.stageNames()).isEqualTo(expectedStages);
		assertThat(String.join(" | ", plan.stages().stream().map(Stage::recipeNames).toList()))
			.isEqualTo(expectedRecipes.replace("~", "com.eottabom.rewrite."));
	}

	// @formatter:off
	static Stream<Arguments> stageRecipeScenarios() {
		return Stream.of(
			Arguments.of(
				"stage 마다 stage 레시피 하나",
				project("3.2.8", "8.14", 17), request("3.3", "keep", false, false),
				"3.3", "~stage.Boot_3_3"
			),
			Arguments.of(
				"--mode=all 은 목표까지의 stage 레시피를 이어 한 stage 로 돈다",
				project("3.0.13", "8.14", 17), request("3.2", "keep", true, false),
				"3.2", "~stage.Boot_3_1, ~stage.Boot_3_2"
			),
			Arguments.of(
				"--mode=all 도 Gradle stage 를 필요한 자리에 넣는다",
				project("2.7.18", "6.9.4", 11), request("4.0", "keep", true, false),
				"4.0", "~stage.Boot_3_0, ~stage.Boot_3_1, ~stage.Boot_3_2, ~stage.Boot_3_3, ~stage.Boot_3_4, "
						+ "~stage.Boot_3_5, ~stage.Gradle_8_14, ~stage.Boot_4_0"
			),
			Arguments.of(
				"custom 레시피를 끄면 upstream stage 와 catalog 규칙만",
				project("3.5.1", "8.14", 17), request("4.0", "keep", false, true),
				"4.0", "~upstream.Boot_4_0, ~upstream.catalog.Boot_4_0"
			),
			Arguments.of(
				"upstream 에 없는 4.1 도 같은 이름의 대체 레시피",
				project("4.0.3", "8.14", 21), request("4.1", "keep", false, true),
				"4.1", "~upstream.Boot_4_1, ~upstream.catalog.Boot_4_1"
			),
			Arguments.of(
				"Java stage 는 stage.Java_NN",
				project("3.5.1", "9.1.0", 21), request("4.0", "latest"),
				"4.0 java25", "~stage.Boot_4_0 | ~stage.Java_25"
			)
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("invalidRequestScenarios")
	void rejectsInvalidRequest(String scenario, ProjectState project, MigrationConfig request,
			String expectedErrorMessage) {
		assertThatThrownBy(() -> this.planner.plan(project, request)).hasMessageContaining(expectedErrorMessage);
	}

	// @formatter:off
	static Stream<Arguments> invalidRequestScenarios() {
		return Stream.of(
			Arguments.of(
				"Java 25는 Boot 3.4 지원 범위 밖",
				project("3.3.5", "8.8", 17), request("3.4", "25"),
				"지원 범위(17 ~ 24) 밖"
			),
			Arguments.of(
				"미지원 목표 버전(3.9) 요청 시 거부",
				project("3.2.0", "8.8", 17), request("3.9", "keep"),
				"목표 버전은"
			),
			Arguments.of(
				"Boot 2.5 보다 낮으면 먼저 2.5 로 올리라고 거부",
				project("2.4.13", "6.8", 11), request("3.0", "keep"),
				"Boot 2.5 이상으로 먼저 올려 주세요"
			),
			Arguments.of(
				"Spring Boot 버전을 찾지 못한 프로젝트 모델 거부",
				project(null, "8.8", 17), request(null, "keep"),
				"Spring Boot 버전을 찾지 못했어요"
			)
		);
	}
	// @formatter:on

	@Test
	void insertsGradleStageWithItsRecipe() {
		Stage gradle = this.planner.plan(project("3.2.8", "8.3", 17), request("3.4", "keep")).stages().get(1);

		assertThat(gradle.id()).isEqualTo(StageId.gradle("8.14"));
		assertThat(gradle.recipes()).containsExactly("com.eottabom.rewrite.stage.Gradle_8_14");
	}

	private static ProjectState project(@Nullable String boot, String gradle, Integer java) {
		return new ProjectState(Path.of("."), boot, gradle, java, java);
	}

	private static MigrationConfig request(@Nullable String boot, @Nullable String java) {
		return request(boot, java, false, false);
	}

	private static MigrationConfig request(@Nullable String boot, @Nullable String java, boolean allAtOnce,
			boolean upstreamOnly) {
		MigrationConfig defaults = MigrationConfig.defaults(Path.of("."));
		return new MigrationConfig(defaults.projectDir(),
				new MigrationConfig.Target(boot, (java != null) ? JavaTarget.parse(java) : JavaTarget.LATEST),
				allAtOnce ? Mode.ALL : Mode.STAGED, defaults.gate(),
				new MigrationConfig.RecipeSettings(!upstreamOnly, true), defaults.build(), false, false);
	}

}
