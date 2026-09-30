package com.eottabom.migration.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.eottabom.migration.MigrationException;
import com.eottabom.migration.io.AtomicFiles;
import com.eottabom.migration.io.SchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * run-state.json 읽기와 쓰기. 읽을 때 schema/run-state.schema.json 으로 검증하고 다른 프로젝트의 기록인지 확인한다.
 */
public record RunStateStore(Path file, Path projectDir, Path schemaDir) {

	private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	public static RunStateStore of(MigrationWorkspace ws, Path schemaDir) {
		return new RunStateStore(ws.runState(), ws.projectDir(), schemaDir);
	}

	/** 대상 프로젝트를 가리키는 값 (기록의 project) */
	public String project() {
		return this.projectDir.toAbsolutePath().normalize().toString();
	}

	public Optional<RunState> read() {
		if (!Files.exists(this.file)) {
			return Optional.empty();
		}
		JsonNode node;
		try {
			node = JSON.readTree(this.file.toFile());
		}
		catch (IOException ex) {
			throw new MigrationException(this.file + " 을 읽지 못했어요. 지우고 다시 실행해 주세요: " + ex.getMessage(), ex);
		}
		validate(node);
		RunState state = JSON.convertValue(node, RunState.class);
		if (!state.project().equals(project())) {
			throw new MigrationException(this.file + " 은 다른 프로젝트(" + state.project() + ")의 기록이에요. "
					+ "이 프로젝트의 기록이 아니면 run-state.json 을 지우고 다시 실행해 주세요");
		}
		return Optional.of(state);
	}

	public void write(RunState state) {
		try {
			AtomicFiles.write(this.file, JSON.writeValueAsString(state) + "\n");
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public void delete() {
		try {
			Files.deleteIfExists(this.file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void validate(JsonNode node) {
		List<String> violations = new SchemaValidator(this.schemaDir).violations("run-state", node);
		if (!violations.isEmpty()) {
			throw new MigrationException(this.file + " 이 run-state.schema.json 에 맞지 않아요. 예전 버전의 기록이면 지우고 다시 실행해 주세요"
					+ SchemaValidator.describe(violations));
		}
	}

}
