package com.eottabom.rewrite;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.Recipe;
import org.openrewrite.config.DeclarativeRecipe;
import org.openrewrite.config.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * yml 레시피가 모두 로딩되고, 참조하는 upstream 레시피 이름과 옵션이 유효한지 검증한다. rewrite-recipe-bom 을 올렸을 때 먼저
 * 깨진다.
 */
class RecipeValidationTests {

	private static final Environment ENV = RecipeEnvironment.get();

	@TestFactory
	Stream<DynamicTest> allCustomRecipesAreValid() {
		List<Recipe> customRecipes = ENV.listRecipes()
			.stream()
			.filter((r) -> r.getName().startsWith("com.eottabom.rewrite."))
			// yml 레시피만 검증한다 (하위 레시피는 validateAll 이 함께 본다). 옵션이 필수인 Java 레시피는 빈 옵션으로 잡혀서
			// 뺀다
			.filter((r) -> r instanceof DeclarativeRecipe)
			.toList();

		assertThat(customRecipes).isNotEmpty();
		return customRecipes.stream()
			.map((recipe) -> DynamicTest.dynamicTest(recipe.getName(),
					() -> assertThat(recipe.validateAll()).as(recipe.getName())
						.allSatisfy((v) -> assertThat(v.isValid()).as(recipe.getName() + " -> " + v).isTrue())));
	}

	/**
	 * 소스 기준 조건(FindTypes, UsesType 등) 아래의 의존성 레시피는 빌드 스크립트에 조건이 맞지 않아 돌지 않는다. 의존성 선언을 다른
	 * 곳에서 챙기는 레시피만 이유와 함께 허용한다.
	 */
	@TestFactory
	Stream<DynamicTest> dependencyRecipesAreNotHiddenBehindSourcePreconditions() {
		Map<String, String> handledElsewhere = Map.of("com.eottabom.rewrite.custom.misc.MigrateCommonsLang2Usages",
				"commons-lang3, commons-text 는 DeclareUsedTransitiveDependencies 가 원본 사용처를 보고 선언한다",
				"com.eottabom.rewrite.custom.elasticsearch.MigrateToRest5Client",
				"httpclient5 는 elasticsearch-java 9 가 api 로 가져오는 elasticsearch-rest5-client 의 compile 의존성이다 (9.2.9 에서 확인)");
		return ENV.listRecipes()
			.stream()
			.filter((r) -> r.getName().startsWith("com.eottabom.rewrite.") && r instanceof DeclarativeRecipe)
			.filter((r) -> preconditions(r).stream().anyMatch((p) -> SOURCE_PRECONDITIONS.contains(p.getName())))
			.map((recipe) -> DynamicTest.dynamicTest(recipe.getName(), () -> {
				List<String> dependencyRecipes = dependencyRecipes(recipe, new HashSet<>());
				if (!handledElsewhere.containsKey(recipe.getName())) {
					assertThat(dependencyRecipes).as(recipe.getName() + " 의 소스 조건 아래 의존성 레시피").isEmpty();
				}
			}));
	}

	private static final Set<String> SOURCE_PRECONDITIONS = Set.of("org.openrewrite.java.search.FindTypes",
			"org.openrewrite.java.search.UsesType", "org.openrewrite.java.search.UsesMethod",
			"org.openrewrite.java.search.HasType", "org.openrewrite.java.search.FindMethods");

	private static List<String> dependencyRecipes(Recipe recipe, Set<String> visited) {
		List<Recipe> children = recipe.getRecipeList();
		List<String> found = new java.util.ArrayList<>();
		for (Recipe child : children) {
			Recipe target = unwrap(child);
			String name = target.getName();
			if (!visited.add(name + "@" + System.identityHashCode(target))) {
				continue;
			}
			if (name.matches(
					"org\\.openrewrite\\.(java\\.dependencies|gradle|maven)\\.(Add|Change|Upgrade|Remove)\\w*Dependenc\\w*")) {
				found.add(name);
			}
			found.addAll(dependencyRecipes(target, visited));
		}
		return found;
	}

	@SuppressWarnings("unchecked")
	private static List<Recipe> preconditions(Recipe recipe) {
		try {
			java.lang.reflect.Field field = DeclarativeRecipe.class.getDeclaredField("preconditions");
			field.setAccessible(true);
			return ((List<Recipe>) field.get(recipe)).stream().map(RecipeValidationTests::unwrap).toList();
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Recipe unwrap(Recipe recipe) {
		if (!recipe.getClass().getName().contains("BellwetherDecorated")) {
			return recipe;
		}
		try {
			java.lang.reflect.Field field = recipe.getClass().getDeclaredField("delegate");
			field.setAccessible(true);
			return (Recipe) field.get(recipe);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@ParameterizedTest(name = "[{index}] Boot {0} stage 레시피 로딩 검증")
	@MethodSource("com.eottabom.rewrite.BootStages#suffixes")
	void stageRecipesExist(String stage) {
		Recipe recipe = ENV.activateRecipes("com.eottabom.rewrite.stage.Boot_" + stage);
		assertThat(recipe.getRecipeList()).as(stage).isNotEmpty();
	}

}
