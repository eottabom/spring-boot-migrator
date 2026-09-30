package com.eottabom.rewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * guides/ 가 가리키는 레시피(recipe, detect)가 실제로 있는지 검증한다. 레시피 이름을 바꾸거나 upstream 레시피가 사라지면 러너의
 * stage 와 결과가 조용히 어긋나므로 여기서 먼저 깨진다.
 */
class GuideRecipesTests {

	private static final Environment ENV = RecipeEnvironment.get();

	private static final Pattern RECIPE_REF = Pattern.compile("(?m)^\\s*(?:-\\s*)?(?:recipe|detect):\\s*([\\w.]+)");

	@TestFactory
	Stream<DynamicTest> guideRecipesExist() throws IOException {
		Set<String> available = ENV.listRecipes().stream().map(Recipe::getName).collect(Collectors.toSet());
		Set<String> referenced = new TreeSet<>();
		List<Path> files;
		try (Stream<Path> walk = Files.walk(BootStages.GUIDES)) {
			files = walk.filter((file) -> file.toString().endsWith(".yml")).toList();
		}
		for (Path file : files) {
			Matcher reference = RECIPE_REF.matcher(Files.readString(file));
			while (reference.find()) {
				referenced.add(reference.group(1));
			}
		}

		assertThat(referenced).hasSizeGreaterThan(10);
		return referenced.stream()
			.map((name) -> DynamicTest.dynamicTest(name, () -> assertThat(available).as(name).contains(name)));
	}

}
