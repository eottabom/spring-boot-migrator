package com.eottabom.migration.recipe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.recipe.ProjectRecipes.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("unchecked")
class AssembledRecipeTests {

	@TempDir
	Path dir;

	@Test
	void tagsDecideStageAndPhaseAndUntaggedRecipesAreBuildingBlocks() throws IOException {
		write(".rewrite/custom/auth.yml", """
				---
				type: specs.openrewrite.org/v1beta/recipe
				name: com.example.MigrateLegacyAuthClient
				tags: ["migration-stage:3.4", "migration-order:before"]
				recipeList:
				  - com.example.RenameAuthPackage
				---
				type: specs.openrewrite.org/v1beta/recipe
				name: com.example.RenameAuthPackage
				recipeList:
				  - org.openrewrite.java.ChangePackage:
				      oldPackageName: com.example.auth.v1
				      newPackageName: com.example.auth.v2
				---
				""");
		write(".rewrite/rewrite.yml", """
				type: specs.openrewrite.org/v1beta/recipe
				name: com.example.EveryStageCleanup
				tags:
				  - migration-stage:*
				recipeList:
				  - org.openrewrite.java.RemoveUnusedImports
				""");

		ProjectRecipes recipes = ProjectRecipes.discover(this.dir);

		assertThat(recipes.files()).hasSize(2);
		assertThat(recipes.documents()).hasSize(3);
		assertThat(recipes.names(List.of("3.4"), Order.BEFORE)).containsExactly("com.example.MigrateLegacyAuthClient");
		assertThat(recipes.names(List.of("3.4"), Order.AFTER)).containsExactly("com.example.EveryStageCleanup");
		assertThat(recipes.names(List.of("3.5"), Order.BEFORE)).isEmpty();
		assertThat(recipes.names(List.of("java21"), Order.AFTER)).containsExactly("com.example.EveryStageCleanup");
	}

	@Test
	void generatedFileWrapsStageRecipeWithProjectRecipes() throws IOException {
		write(".rewrite/custom/auth.yml", """
				type: specs.openrewrite.org/v1beta/recipe
				name: com.example.MigrateLegacyAuthClient
				tags: ["migration-stage:3.4", "migration-order:before"]
				recipeList:
				  - org.openrewrite.java.RemoveUnusedImports
				""");
		Stage stage = new Stage(Stage.Kind.BOOT, "3.4", "com.eottabom.rewrite.stage.Boot_3_4");

		AssembledRecipe.Assembled generated = AssembledRecipe.write(this.dir, "product-api", stage, stage.tag(3),
				ProjectRecipes.discover(this.dir));

		assertThat(generated.name()).isEqualTo("migration.assembled.Stage_03_boot_3_4");
		assertThat(generated.file()).isEqualTo(this.dir.resolve(".rewrite/rewrite.assembled.yml"));
		List<Map<String, Object>> docs = new ArrayList<>();
		new Yaml().loadAll(Files.readString(generated.file())).forEach((doc) -> docs.add((Map<String, Object>) doc));
		assertThat(docs).hasSize(2);
		assertThat(docs.get(0)).containsEntry("name", generated.name());
		assertThat((List<Object>) docs.get(0).get("recipeList")).containsExactly("com.example.MigrateLegacyAuthClient",
				"com.eottabom.rewrite.stage.Boot_3_4");
		assertThat(docs.get(1)).containsEntry("name", "com.example.MigrateLegacyAuthClient");
	}

	@Test
	void generatesStageRecipeWithoutProjectRecipes() {
		Stage stage = new Stage(Stage.Kind.GRADLE, "gradle8.14", "com.eottabom.rewrite.stage.Gradle_8_14");

		AssembledRecipe.Assembled generated = AssembledRecipe.write(this.dir, "p", stage, stage.tag(1),
				ProjectRecipes.discover(this.dir));

		assertThat(generated.name()).isEqualTo("migration.assembled.Stage_01_gradle8_14");
	}

	@ParameterizedTest
	@ValueSource(strings = { "migration-stage:4.0.x", "migration-order:befor" })
	void failsWithFileNameOnMistypedTag(String tag) throws IOException {
		write(".rewrite/custom/auth.yml", """
				type: specs.openrewrite.org/v1beta/recipe
				name: com.example.MigrateLegacyAuthClient
				tags: ["migration-stage:4.0", "%s"]
				recipeList:
				  - org.openrewrite.java.RemoveUnusedImports
				""".formatted(tag));

		assertThatThrownBy(() -> ProjectRecipes.discover(this.dir)).hasMessageContaining("auth.yml")
			.hasMessageContaining(tag);
	}

	@Test
	void failsWithFileNameOnInvalidYaml() throws IOException {
		write(".rewrite/rewrite.yml", "type: [broken\n");

		assertThatThrownBy(() -> ProjectRecipes.discover(this.dir)).hasMessageContaining(".rewrite/rewrite.yml");
	}

	private void write(String path, String content) throws IOException {
		Path file = this.dir.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

}
