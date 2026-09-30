package com.eottabom.migration.io;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * schema/ 의 JSON Schema 로 가이드, 설정, 재개 기록을 검증한다.
 *
 * @param schemaDir schema/
 */
public record SchemaValidator(Path schemaDir) {

	private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

	/**
	 * @param schemaName 확장자를 뺀 스키마 이름. 예) config, boot-guide, run-state
	 * @return 맞지 않는 곳을 설명하는 줄 (정렬). 맞으면 빈 목록
	 */
	public List<String> violations(String schemaName, JsonNode node) {
		JsonSchema schema = SCHEMAS
			.getSchema(SchemaLocation.of(this.schemaDir.resolve(fileName(schemaName)).toUri().toString()));
		return schema.validate(node).stream().map(ValidationMessage::getMessage).sorted().toList();
	}

	public static String fileName(String schemaName) {
		return schemaName + ".schema.json";
	}

	/** 오류 메시지에 붙이는 형태 (한 줄에 하나씩 들여 쓴다) */
	public static String describe(List<String> violations) {
		return "\n  " + String.join("\n  ", violations);
	}

}
