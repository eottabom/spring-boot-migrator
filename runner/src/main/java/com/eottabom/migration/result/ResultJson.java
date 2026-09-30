package com.eottabom.migration.result;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import com.eottabom.migration.io.AtomicFiles;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 결과 JSON 읽기와 쓰기. record 의 구성 요소만 쓴다 (isEmpty 같은 도움 메서드는 쓰지 않는다).
 */
final class ResultJson {

	private static final ObjectMapper JSON = JsonMapper.builder()
		.visibility(PropertyAccessor.ALL, Visibility.NONE)
		.visibility(PropertyAccessor.FIELD, Visibility.ANY)
		.enable(SerializationFeature.INDENT_OUTPUT)
		.build();

	private ResultJson() {
	}

	static void write(Path file, Object value) {
		try {
			AtomicFiles.write(file, JSON.writeValueAsString(value) + "\n");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static JsonNode read(Path file) {
		try {
			return JSON.readTree(file.toFile());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static ObjectMapper mapper() {
		return JSON;
	}

}
