package com.eottabom.migration.recipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * OpenRewrite 선언형 YAML 문서 하나. 프로젝트가 가진 문서는 내용을 모르는 채로 조립 파일에 그대로 옮겨야 해서 YAML 구조를 그대로 들고,
 * 러너가 보는 값만 이름 있는 메서드로 꺼낸다.
 */
public record RecipeDocument(Map<String, Object> yaml) {

	private static final String RECIPE_TYPE = "specs.openrewrite.org/v1beta/recipe";

	static RecipeDocument recipe(String name, String displayName, String description, List<String> recipeList) {
		Map<String, Object> yaml = new LinkedHashMap<>();
		yaml.put("type", RECIPE_TYPE);
		yaml.put("name", name);
		yaml.put("displayName", displayName);
		yaml.put("description", description);
		yaml.put("recipeList", recipeList);
		return new RecipeDocument(yaml);
	}

	/** 스타일 같은 다른 종류의 문서가 아니라 이름 있는 레시피다 */
	boolean isNamedRecipe() {
		return RECIPE_TYPE.equals(this.yaml.get("type")) && this.yaml.get("name") != null;
	}

	String name() {
		return String.valueOf(this.yaml.get("name"));
	}

	/** tags 가 없거나 목록이 아니면 빈 목록 */
	List<String> tags() {
		@Nullable Object tags = this.yaml.get("tags");
		return (tags instanceof List<?> list) ? list.stream().map((tag) -> String.valueOf(tag).trim()).toList()
				: List.of();
	}

}
