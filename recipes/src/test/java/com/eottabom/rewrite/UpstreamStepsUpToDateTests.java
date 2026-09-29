package com.eottabom.rewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성 파일이 지금 레시피로 만든 결과와 같은지. 다르면 ./gradlew syncUpstreamSteps
 */
class UpstreamStepsUpToDateTests {

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("generatedFiles")
	void generatedFileIsUpToDate(Path file, Supplier<String> generator) throws IOException {
		assertThat(Files.readString(file)).as("레시피가 바뀌었다. ./gradlew syncUpstreamSteps 로 다시 만든다")
			.isEqualTo(generator.get());
	}

	/** 단계 레시피가 직전 단계 체인에서 이미 돈 옵션 없는 레시피를 다시 돌리지 않는다 */
	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "3.1", "3.2", "3.3", "3.4", "3.5", "4.0" })
	@SuppressWarnings("unchecked")
	void stepDoesNotRerunRecipesOfPreviousStage(String stage) throws IOException {
		Map<String, Map<String, Object>> recipes = new LinkedHashMap<>(UpstreamStepsGenerator.upstreamRecipes());
		for (Object doc : new Yaml().loadAll(Files.readString(UpstreamStepsGenerator.OUTPUT))) {
			if (doc instanceof Map<?, ?> map && map.get("name") != null) {
				recipes.put(String.valueOf(map.get("name")), (Map<String, Object>) map);
			}
		}
		Set<String> ran = UpstreamStepsGenerator.closure(UpstreamStepsGenerator.previousOf(stage), recipes);
		Set<String> step = UpstreamStepsGenerator.closure(UpstreamStepsGenerator.STEP_PREFIX + stage.replace('.', '_'),
				recipes);

		assertThat(step).doesNotContainAnyElementsOf(ran);
	}

	static Stream<Arguments> generatedFiles() {
		return Stream.of(
				Arguments.of(UpstreamStepsGenerator.OUTPUT, (Supplier<String>) UpstreamStepsGenerator::generate),
				Arguments.of(VersionCatalogStepsGenerator.OUTPUT,
						(Supplier<String>) VersionCatalogStepsGenerator::generate));
	}

}
