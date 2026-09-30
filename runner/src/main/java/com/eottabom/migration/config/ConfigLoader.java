package com.eottabom.migration.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.eottabom.migration.MigrationException;
import com.eottabom.migration.config.MigrationConfig.BuildSettings;
import com.eottabom.migration.config.MigrationConfig.GateSettings;
import com.eottabom.migration.config.MigrationConfig.Jdk;
import com.eottabom.migration.config.MigrationConfig.RecipeSettings;
import com.eottabom.migration.config.MigrationConfig.Target;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.jspecify.annotations.Nullable;

/**
 * 설정 파일과 CLI 값을 합쳐 {@link MigrationConfig} 를 만든다. 합친 값을 schema/config.schema.json 으로
 * 검증하고, 없는 값은 {@link MigrationConfig#defaults(Path)} 를 쓴다. CLI 값이 파일보다 우선한다.
 */
public final class ConfigLoader {

	/** 대상 프로젝트 루트에 있으면 읽는 설정 파일 */
	public static final String FILE_NAME = "spring-boot-migrator.yml";

	private static final ObjectMapper YAML = new YAMLMapper();

	private final Path schemaDir;

	public ConfigLoader(Path schemaDir) {
		this.schemaDir = schemaDir;
	}

	/**
	 * @param configFile --config 로 준 파일. null 이면 대상 프로젝트의 spring-boot-migrator.yml (없으면
	 * 기본값만)
	 * @param cli 설정 파일과 같은 구조로 담은 CLI 옵션
	 */
	public MigrationConfig load(Path projectDir, @Nullable Path configFile, ObjectNode cli) {
		Path file = (configFile != null) ? configFile : projectDir.resolve(FILE_NAME);
		if (configFile != null && !Files.isRegularFile(configFile)) {
			throw new MigrationException("설정 파일이 없어요: " + configFile);
		}
		ObjectNode merged = Files.isRegularFile(file) ? read(file) : YAML.createObjectNode();
		merge(merged, cli);
		validate(merged, Files.isRegularFile(file) ? file + " 와 CLI 옵션" : "CLI 옵션");
		return toConfig(projectDir, merged);
	}

	private static ObjectNode read(Path file) {
		try {
			JsonNode node = YAML.readTree(file.toFile());
			if (node == null || node.isMissingNode() || node.isNull()) {
				return YAML.createObjectNode();
			}
			if (!(node instanceof ObjectNode object)) {
				throw new MigrationException(file + " 은 키와 값으로 된 YAML 이어야 해요");
			}
			return object;
		}
		catch (IOException ex) {
			throw new MigrationException(file + " 을 읽지 못했어요: " + ex.getMessage(), ex);
		}
	}

	/** override 의 값으로 base 를 덮는다. 객체는 키별로 합친다 */
	static void merge(ObjectNode base, JsonNode override) {
		for (Map.Entry<String, JsonNode> field : override.properties()) {
			JsonNode current = base.get(field.getKey());
			if (current instanceof ObjectNode object && field.getValue().isObject()) {
				merge(object, field.getValue());
			}
			else {
				base.set(field.getKey(), field.getValue());
			}
		}
	}

	private void validate(JsonNode config, String source) {
		JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
			.getSchema(SchemaLocation.of(this.schemaDir.resolve("config.schema.json").toUri().toString()));
		Set<ValidationMessage> errors = schema.validate(config);
		if (!errors.isEmpty()) {
			throw new MigrationException(source + " 의 값이 맞지 않아요 (schema/config.schema.json)\n  "
					+ errors.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.joining("\n  ")));
		}
	}

	private static MigrationConfig toConfig(Path projectDir, JsonNode node) {
		MigrationConfig defaults = MigrationConfig.defaults(projectDir);
		JsonNode target = node.path("target");
		JsonNode gate = node.path("gate");
		JsonNode recipes = node.path("recipes");
		JsonNode build = node.path("build");
		return new MigrationConfig(projectDir,
				new Target(text(target, "boot", defaults.target().boot()),
						target.has("java") ? JavaTarget.parse(target.get("java").asText()) : defaults.target().java()),
				node.has("mode") ? Mode.parse(node.get("mode").asText()) : defaults.mode(),
				new GateSettings(
						gate.has("level") ? GateLevel.parse(gate.get("level").asText()) : defaults.gate().level(),
						gate.path("testRetries").asInt(defaults.gate().testRetries()),
						gate.path("baselineTests").asBoolean(defaults.gate().baselineTests())),
				new RecipeSettings(recipes.path("custom").asBoolean(defaults.recipes().custom()),
						recipes.path("project").asBoolean(defaults.recipes().project())),
				new BuildSettings(build.has("jdk") ? Jdk.parse(build.get("jdk").asText()) : defaults.build().jdk(),
						text(build, "jvmArgs", defaults.build().jvmArgs()),
						build.has("timeoutMinutes") ? Duration.ofMinutes(build.get("timeoutMinutes").asLong())
								: defaults.build().timeout()),
				node.path("commit").asBoolean(defaults.commit()),
				node.path("allowDirty").asBoolean(defaults.allowDirty()));
	}

	private static @Nullable String text(JsonNode node, String field, @Nullable String fallback) {
		JsonNode value = node.get(field);
		return (value != null && !value.isNull()) ? value.asText() : fallback;
	}

	/** CLI 옵션을 담을 빈 값 */
	public static ObjectNode cli() {
		return YAML.createObjectNode();
	}

}
