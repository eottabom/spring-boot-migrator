package com.eottabom.migration.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.eottabom.migration.config.MigrationConfig.Jdk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTests {

	private static final Path SCHEMA = Path.of("../schema");

	private final ConfigLoader loader = new ConfigLoader(SCHEMA);

	@TempDir
	Path project;

	@Test
	void usesDefaultsWithoutConfigFile() {
		assertThat(this.loader.load(this.project, null, ConfigLoader.cli()))
			.isEqualTo(MigrationConfig.defaults(this.project));
	}

	@Test
	void defaultsMatchSchemaDefaults() throws IOException {
		JsonNode schema = new ObjectMapper().readTree(SCHEMA.resolve("config.schema.json").toFile());
		JsonNode properties = schema.get("properties");
		MigrationConfig defaults = MigrationConfig.defaults(this.project);

		assertThat(properties.at("/target/properties/java/default").asText())
			.isEqualTo(defaults.target().java().toString());
		assertThat(properties.at("/mode/default").asText()).isEqualTo(defaults.mode().option());
		assertThat(properties.at("/gate/properties/level/default").asText())
			.isEqualTo(defaults.gate().level().option());
		assertThat(properties.at("/gate/properties/testRetries/default").asInt())
			.isEqualTo(defaults.gate().testRetries());
		assertThat(properties.at("/gate/properties/baselineTests/default").asBoolean())
			.isEqualTo(defaults.gate().baselineTests());
		assertThat(properties.at("/recipes/properties/custom/default").asBoolean())
			.isEqualTo(defaults.recipes().custom());
		assertThat(properties.at("/recipes/properties/project/default").asBoolean())
			.isEqualTo(defaults.recipes().project());
		assertThat(properties.at("/build/properties/jdk/default").asText()).isEqualTo("auto");
		assertThat(properties.at("/build/properties/timeoutMinutes/default").asLong())
			.isEqualTo(defaults.build().timeout().toMinutes());
		assertThat(properties.at("/commit/default").asBoolean()).isEqualTo(defaults.commit());
		assertThat(properties.at("/allowDirty/default").asBoolean()).isEqualTo(defaults.allowDirty());
	}

	@Test
	void readsProjectConfigFileAndLetsCliOverrideIt() throws IOException {
		Files.writeString(this.project.resolve("spring-boot-migrator.yml"), """
				target:
				  boot: "3.5"
				  java: 21
				mode: all
				gate:
				  level: compile
				  testRetries: 3
				  baselineTests: false
				recipes: { custom: false, project: false }
				build: { jdk: current, jvmArgs: "-Xmx2g", timeoutMinutes: 0 }
				commit: true
				allowDirty: true
				""");
		ObjectNode cli = ConfigLoader.cli();
		cli.putObject("target").put("boot", "4.0");
		cli.put("mode", "preview");

		MigrationConfig config = this.loader.load(this.project, null, cli);

		assertThat(config.target().boot()).isEqualTo("4.0");
		assertThat(config.target().java()).hasToString("21");
		assertThat(config.mode()).isEqualTo(Mode.PREVIEW);
		assertThat(config.preview()).isTrue();
		assertThat(config.gate()).isEqualTo(new MigrationConfig.GateSettings(GateLevel.COMPILE, 3, false));
		assertThat(config.recipes()).isEqualTo(new MigrationConfig.RecipeSettings(false, false));
		assertThat(config.build()).isEqualTo(new MigrationConfig.BuildSettings(Jdk.CURRENT, "-Xmx2g", Duration.ZERO));
		assertThat(config.build().currentJavaHome()).isTrue();
		assertThat(config.commit()).isTrue();
		assertThat(config.allowDirty()).isTrue();
		assertThat(config.summary(21)).isEqualTo("gate=compile, mode=preview, commit, java=21, recipes.custom=false, "
				+ "recipes.project=false, allow-dirty, gate.baselineTests=false, gate.testRetries=3, build.jdk=current");
	}

	@Test
	void readsConfigFileGivenByOption() throws IOException {
		Path file = Files.writeString(this.project.resolve("other.yml"), "mode: all\n");

		MigrationConfig config = this.loader.load(this.project, file, ConfigLoader.cli());

		assertThat(config.allAtOnce()).isTrue();
		assertThat(config.summary(null)).isEqualTo("gate=build, mode=all");
	}

	@Test
	void treatsEmptyConfigFileAsDefaults() throws IOException {
		Files.writeString(this.project.resolve("spring-boot-migrator.yml"), "");

		assertThat(this.loader.load(this.project, null, ConfigLoader.cli()))
			.isEqualTo(MigrationConfig.defaults(this.project));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', value = { "mode: fast|mode", "gate: { testRetries: -1 }|testRetries",
			"target: { boot: 4 }|boot", "unknown: true|unknown", "build: { jdk: jdk21 }|jdk" })
	void rejectsValuesThatDoNotMatchSchema(String content, String field) throws IOException {
		Files.writeString(this.project.resolve("spring-boot-migrator.yml"), content);

		assertThatThrownBy(() -> this.loader.load(this.project, null, ConfigLoader.cli()))
			.hasMessageContaining("config.schema.json")
			.hasMessageContaining(field);
	}

	@Test
	void rejectsInvalidCliValue() {
		ObjectNode cli = ConfigLoader.cli();
		cli.putObject("gate").put("level", "test");

		assertThatThrownBy(() -> this.loader.load(this.project, null, cli)).hasMessageContaining("CLI 옵션")
			.hasMessageContaining("level");
	}

	@Test
	void rejectsMissingOrMalformedConfigFile() throws IOException {
		assertThatThrownBy(() -> this.loader.load(this.project, this.project.resolve("none.yml"), ConfigLoader.cli()))
			.hasMessageContaining("설정 파일이 없어요");
		Path list = Files.writeString(this.project.resolve("list.yml"), "- a\n");
		assertThatThrownBy(() -> this.loader.load(this.project, list, ConfigLoader.cli()))
			.hasMessageContaining("키와 값으로 된 YAML");
		Path broken = Files.writeString(this.project.resolve("broken.yml"), "mode: [\n");
		assertThatThrownBy(() -> this.loader.load(this.project, broken, ConfigLoader.cli()))
			.hasMessageContaining("을 읽지 못했어요");
	}

}
