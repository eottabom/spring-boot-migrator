package com.eottabom.rewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선언형 레시피 yml 의 구조가 schema/rewrite-recipe.schema.json 에 맞는지. OpenRewrite 는 모르는 키를 조용히
 * 무시해서 preconditions 를 잘못 쓴 레시피도 유효하다고 보고 조건 없이 돌린다. 레시피 이름과 옵션은
 * {@link RecipeValidationTests} 가 본다.
 */
class RecipeSchemaTests {

	private static final YAMLMapper YAML = new YAMLMapper();

	private static final JsonSchema SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
		.getSchema(SchemaLocation.of(RecipeFiles.SCHEMA.toUri().toString()));

	private static final String VALID = """
			type: specs.openrewrite.org/v1beta/recipe
			name: com.example.Sample
			displayName: 예시
			description: 예시.
			preconditions:
			  - org.openrewrite.java.search.FindTypes:
			      fullyQualifiedTypeName: a.B
			recipeList:
			  - org.openrewrite.java.ChangeType:
			      oldFullyQualifiedTypeName: a.B
			      newFullyQualifiedTypeName: a.C
			  - org.openrewrite.java.RemoveUnusedImports
			""";

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("com.eottabom.rewrite.RecipeFiles#all")
	void recipeFileMatchesSchema(Path file) throws IOException {
		String content = Files.readString(file);

		assertThat(content).as("첫 줄에 스키마를 적는다").startsWith(RecipeFiles.SCHEMA_COMMENT + "\n");
		List<JsonNode> documents = documents(content);
		assertThat(documents).isNotEmpty();
		for (JsonNode document : documents) {
			assertThat(SCHEMA.validate(document)).as(document.path("name").asText()).isEmpty();
		}
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("structuralMistakes")
	void schemaRejectsStructuralMistakes(String scenario, String original, String mistake, String reportedAt)
			throws IOException {
		assertThat(VALID).contains(original);
		assertThat(SCHEMA.validate(documents(VALID).get(0))).isEmpty();

		Set<ValidationMessage> errors = SCHEMA.validate(documents(VALID.replace(original, mistake)).get(0));

		assertThat(errors).as(scenario).isNotEmpty();
		assertThat(errors).extracting(ValidationMessage::getMessage)
			.anyMatch((message) -> message.contains(reportedAt));
	}

	static Stream<Arguments> structuralMistakes() {
		String options = "      oldFullyQualifiedTypeName: a.B\n      newFullyQualifiedTypeName: a.C";
		String unusedImports = "- org.openrewrite.java.RemoveUnusedImports";
		return Stream.of(Arguments.of("preconditions 오타", "preconditions:", "precondition:", "precondition"),
				Arguments.of("옵션을 이름과 같은 깊이에 씀", options, options.replace("      ", "    "), "recipeList"),
				Arguments.of("옵션 없이 콜론만 남김", unusedImports, unusedImports + ":", "recipeList"),
				Arguments.of("레시피 이름이 점 없는 한 단어", unusedImports, "- RemoveUnusedImports", "recipeList"),
				Arguments.of("recipeList 없음", "recipeList:", "recipes:", "recipeList"),
				Arguments.of("type 이 레시피가 아님", "v1beta/recipe", "v1beta/style", "type"));
	}

	private static List<JsonNode> documents(String yaml) throws IOException {
		List<JsonNode> documents = new ArrayList<>();
		try (MappingIterator<JsonNode> iterator = YAML.readerFor(JsonNode.class).readValues(yaml)) {
			while (iterator.hasNext()) {
				JsonNode document = iterator.next();
				if (!document.isNull() && !document.isMissingNode()) {
					documents.add(document);
				}
			}
		}
		return documents;
	}

}
