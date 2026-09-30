package com.eottabom.rewrite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 이 저장소의 선언형 레시피 yml (src/main/resources/META-INF/rewrite). 손으로 쓴 파일과 생성 파일 모두 첫 줄에 같은
 * 스키마를 가리킨다.
 */
final class RecipeFiles {

	static final Path ROOT = Path.of("src/main/resources/META-INF/rewrite");

	static final Path SCHEMA = Path.of("../schema/rewrite-recipe.schema.json");

	/** IDE(yaml-language-server)가 읽는 첫 줄. yml 은 ROOT 아래 한 단계 폴더에 둔다 */
	static final String SCHEMA_COMMENT = "# yaml-language-server: $schema=../../../../../../../schema/rewrite-recipe.schema.json";

	private RecipeFiles() {
	}

	static List<Path> all() {
		try (Stream<Path> walk = Files.walk(ROOT)) {
			return walk.filter((path) -> path.toString().endsWith(".yml")).sorted().toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
