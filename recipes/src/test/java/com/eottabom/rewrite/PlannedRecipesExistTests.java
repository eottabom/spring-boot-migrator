package com.eottabom.rewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 러너(MigrationPlanner)가 고르는 단계 레시피가 이 저장소의 yml 에 선언돼 있는지. 이름은 러너 코드와 같은 규칙으로 만든다.
 */
class PlannedRecipesExistTests {

	private static final Set<String> DECLARED = new HashSet<>();

	@BeforeAll
	static void readDeclaredRecipes() throws IOException {
		try (Stream<Path> files = Files.walk(Path.of("src/main/resources/META-INF/rewrite"))) {
			for (Path file : files.filter((f) -> f.toString().endsWith(".yml")).toList()) {
				for (Object doc : new Yaml().loadAll(Files.readString(file))) {
					if (doc instanceof Map<?, ?> map && map.get("name") != null) {
						DECLARED.add(String.valueOf(map.get("name")));
					}
				}
			}
		}
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("plannedRecipes")
	void plannedRecipeIsDeclared(String recipe) {
		assertThat(DECLARED).contains(recipe);
	}

	static Stream<String> plannedRecipes() throws IOException {
		Stream<String> boot = BootStages.suffixes()
			.stream()
			.flatMap((stage) -> Stream.of("com.eottabom.rewrite.stage.Boot_" + stage,
					"com.eottabom.rewrite.upstream.Boot_" + stage,
					"com.eottabom.rewrite.upstream.catalog.Boot_" + stage));
		Stream<String> gradle = guideVersions("gradle")
			.map((version) -> "com.eottabom.rewrite.stage.Gradle_" + version.replace('.', '_'));
		Stream<String> java = guideVersions("java").map((version) -> "com.eottabom.rewrite.stage.Java_" + version);
		return Stream.concat(boot, Stream.concat(gradle, java));
	}

	private static Stream<String> guideVersions(String kind) throws IOException {
		try (Stream<Path> files = Files.list(BootStages.GUIDES.resolve(kind))) {
			return files.map((file) -> file.getFileName().toString().replace(".yml", "")).toList().stream();
		}
	}

}
