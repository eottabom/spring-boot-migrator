package com.eottabom.migration.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * stage 폴더 (03-boot-3.4/) 의 파일.
 */
public record StageFiles(Path dir) {

	public StageFiles {
		try {
			Files.createDirectories(dir);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 러너가 조립해 돌린 레시피 (.rewrite/rewrite.assembled.yml 의 사본) */
	public Path assembledRecipe() {
		return this.dir.resolve("assembled.yml");
	}

	public Path rewriteLog() {
		return this.dir.resolve("rewrite.log");
	}

	public Path compileLog() {
		return this.dir.resolve("compile.log");
	}

	/** deprecated API 를 대체 레시피로 바꾸기 전의 컴파일 로그 */
	public Path compileBeforeDeprecationsLog() {
		return this.dir.resolve("compile-before-deprecations.log");
	}

	public Path deprecationsLog() {
		return this.dir.resolve("deprecations.log");
	}

	public Path buildLog() {
		return this.dir.resolve("build.log");
	}

	/** stage 후 resolve 된 의존성 버전 */
	public Path versions() {
		return this.dir.resolve("versions.txt");
	}

	public Path failedTasks() {
		return this.dir.resolve("failed-tasks.txt");
	}

	public Path testDirs() {
		return this.dir.resolve("test-dirs.txt");
	}

	public Path retryLog(int attempt) {
		return this.dir.resolve("retry-" + attempt + ".log");
	}

	public Path retryTestDirs(int attempt) {
		return this.dir.resolve("retry-" + attempt + ".test-dirs.txt");
	}

	/** 다시 돌리기 전에 build 의 테스트 결과를 복사해 두는 곳 */
	public Path keptResults() {
		return this.dir.resolve("results-kept");
	}

	public Path resultMarkdown() {
		return this.dir.resolve("result.md");
	}

	/** result.html 이 읽는 데이터 (schema/result.schema.json) */
	public Path resultJson() {
		return this.dir.resolve("result.json");
	}

	/** 시작 시점 대비 누적 변경. 재개할 때 stage 전 상태로 되돌리는 데도 쓴다 */
	public Path cumulativePatch() {
		return this.dir.resolve("cumulative.patch");
	}

	/** 이 stage 에서만 바뀐 diff */
	public Path stagePatch() {
		return this.dir.resolve("stage.patch");
	}

	/** --mode=preview 로 만든 변경 */
	public Path previewPatch() {
		return this.dir.resolve("preview.patch");
	}

	public Path commitLog() {
		return this.dir.resolve("commit.log");
	}

}
