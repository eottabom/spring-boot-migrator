package com.eottabom.migration.guide;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.eottabom.migration.misc.Versions;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.jspecify.annotations.Nullable;

/**
 * guides/ 의 YAML 을 읽는다. 파일마다 schema/ 의 JSON Schema 로 검증하고, 파일 이름의 버전과 스키마가 정한 기본값을 채운 뒤
 * 레코드로 옮긴다.
 */
final class GuideReader {

	private static final ObjectMapper YAML = JsonMapper.builder(new YAMLFactory())
		.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private final Path schemaDir;

	private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

	GuideReader(Path schemaDir) {
		this.schemaDir = schemaDir;
	}

	/** dir 의 *.yml 을 파일 이름의 버전 순서로 읽는다. 디렉토리가 없으면 빈 목록 */
	<T> List<T> readAll(Path dir, String schema, Class<T> type) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter((file) -> file.getFileName().toString().endsWith(".yml"))
				.sorted(Comparator.comparing(GuideReader::baseName, Versions::compare))
				.map((file) -> read(file, schema, type, dir.getFileName() + "-" + baseName(file)))
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * @param guideId 체크리스트 항목 id 앞에 붙는 이름 (boot-3.4, common)
	 */
	<T> T read(Path file, String schema, Class<T> type, String guideId) {
		JsonNode node = parse(file);
		validate(file, schema, node);
		ObjectNode object = (ObjectNode) node;
		object.put("version", baseName(file));
		fillDefaults(object, guideId);
		try {
			return YAML.treeToValue(object, type);
		}
		catch (IOException ex) {
			throw new IllegalArgumentException(file + ": " + ex.getMessage(), ex);
		}
	}

	private static JsonNode parse(Path file) {
		try {
			JsonNode node = YAML.readTree(file.toFile());
			// 빈 파일은 스키마가 요구하는 필드가 없다고 알리도록 빈 객체로 본다
			return (node == null || node.isMissingNode()) ? YAML.createObjectNode() : node;
		}
		catch (IOException ex) {
			throw new IllegalArgumentException(file + " 을 읽지 못했어요: " + ex.getMessage(), ex);
		}
	}

	private void validate(Path file, String schema, JsonNode node) {
		JsonSchema jsonSchema = this.schemas
			.getSchema(SchemaLocation.of(this.schemaDir.resolve(schema + ".schema.json").toUri().toString()));
		Set<ValidationMessage> errors = jsonSchema.validate(node);
		if (!errors.isEmpty()) {
			throw new IllegalArgumentException(file + " 이 " + schema + ".schema.json 에 맞지 않아요\n  "
					+ errors.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.joining("\n  ")));
		}
	}

	/** 목록 필드가 없으면 빈 목록, 체크리스트 항목의 fix 는 manual, source 는 파일의 source */
	private static void fillDefaults(ObjectNode guide, String guideId) {
		for (String list : List.of("checklist", "failureHints", "deprecations")) {
			if (!guide.has(list)) {
				guide.putArray(list);
			}
		}
		@Nullable JsonNode source = guide.get("source");
		for (JsonNode item : (ArrayNode) guide.get("checklist")) {
			ObjectNode checklistItem = (ObjectNode) item;
			checklistItem.put("id", guideId + "/" + checklistItem.get("id").asText());
			if (!checklistItem.has("fix")) {
				checklistItem.put("fix", "manual");
			}
			if (!checklistItem.has("source") && source != null) {
				checklistItem.set("source", source);
			}
			if (!checklistItem.has("affected")) {
				checklistItem.putArray("affected");
			}
		}
	}

	private static String baseName(Path file) {
		String name = file.getFileName().toString();
		return name.substring(0, name.lastIndexOf('.'));
	}

}
