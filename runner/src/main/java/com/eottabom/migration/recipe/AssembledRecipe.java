package com.eottabom.migration.recipe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.recipe.ProjectRecipes.Order;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * stage 마다 실행할 레시피를 대상 프로젝트의 .rewrite/rewrite.assembled.yml 로 만든다 (러너 관리, 매 stage 덮어쓴다).
 *
 * <pre>
 * migration.assembled.Stage_03_boot_3_4
 *   ├─ 프로젝트 레시피 (migration-order:before)
 *   ├─ stage 레시피 (stage.Boot_3_4, --mode=all 이면 목표까지의 stage 레시피 전부)
 *   └─ 프로젝트 레시피 (migration-order:after)
 * </pre> OpenRewrite Gradle 플러그인은 설정 파일을 하나만 읽으므로, 프로젝트의 레시피 문서도 이 파일에 함께 넣는다. 개발자가 관리하는
 * 원본 파일은 건드리지 않는다.
 */
public final class AssembledRecipe {

	public static final String RELATIVE_PATH = ".rewrite/rewrite.assembled.yml";

	private AssembledRecipe() {
	}

	public static Assembled write(Path projectDir, String projectName, Stage stage, String tag,
			ProjectRecipes projectRecipes) {
		String name = "migration.assembled.Stage_" + tag.replaceAll("[^A-Za-z0-9]", "_");
		List<String> before = projectRecipes.names(stage.covers(), Order.BEFORE);
		List<String> after = projectRecipes.names(stage.covers(), Order.AFTER);
		List<String> recipeList = new ArrayList<>(before);
		recipeList.addAll(stage.recipes());
		recipeList.addAll(after);
		List<RecipeDocument> documents = new ArrayList<>();
		documents
			.add(assembled(name, "Assembled migration for " + projectName + " (" + stage.name() + ")", recipeList));
		documents.addAll(projectRecipes.documents());
		String sources = projectRecipes.files().isEmpty() ? "없음" : String.join(", ",
				projectRecipes.files().stream().map((file) -> projectDir.relativize(file).toString()).toList());
		Path file = writeFile(projectDir, "# 프로젝트 레시피 원본: " + sources + "\n", documents);
		return new Assembled(name, file);
	}

	/** 레시피 목록만 묶는다 (deprecated API 대체처럼 프로젝트 레시피 없이 한 번 더 돌 때) */
	public static Assembled write(Path projectDir, String name, List<String> recipes) {
		Path file = writeFile(projectDir, "", List.of(assembled(name, name, recipes)));
		return new Assembled(name, file);
	}

	private static RecipeDocument assembled(String name, String displayName, List<String> recipeList) {
		return RecipeDocument.recipe(name, displayName, "migrationRun 이 만든 파일이에요. 매 stage 덮어쓰니 직접 고치지 마세요.",
				recipeList);
	}

	private static Path writeFile(Path projectDir, String header, List<RecipeDocument> documents) {
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setAllowUnicode(true);
		options.setWidth(160);
		String yaml = "# 러너(migrationRun)가 stage 마다 만들어요. 직접 고치지 마세요.\n" + header
				+ new Yaml(options).dumpAll(documents.stream().map(RecipeDocument::yaml).iterator());
		Path file = projectDir.resolve(RELATIVE_PATH);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, yaml);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return file;
	}

	/**
	 * @param name 활성화할 레시피 이름 (-Drewrite.activeRecipe)
	 */
	public record Assembled(String name, Path file) {
	}

}
