package com.eottabom.migration.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * stage 밖에서 한 번 모으는 파일 (start/, scan/). 대상 Gradle 이 -P 인자로 받은 경로에 쓴다.
 */
public record RunFiles(Path dir) {

	public RunFiles {
		try {
			Files.createDirectories(dir);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** resolve 된 의존성 버전 (group:artifact=version) */
	public Path versions() {
		return this.dir.resolve("versions.txt");
	}

	public Path versionsLog() {
		return this.dir.resolve("versions.log");
	}

	/** 빌드 파일에서 Boot 버전을 찾지 못했을 때 대상 Gradle 에게 물은 의존성 버전 */
	public Path inspectVersions() {
		return this.dir.resolve("inspect-versions.txt");
	}

	public Path inspectVersionsLog() {
		return this.dir.resolve("inspect-versions.log");
	}

	/** detect 레시피가 ~~&gt; 로 표시한 patch */
	public Path detectPatch() {
		return this.dir.resolve("detect.patch");
	}

	public Path detectLog() {
		return this.dir.resolve("detect.log");
	}

	public Path baselineBuildLog() {
		return this.dir.resolve("baseline-build.log");
	}

	public Path baselineFailedTasks() {
		return this.dir.resolve("baseline-failed-tasks.txt");
	}

	public Path baselineTestDirs() {
		return this.dir.resolve("baseline-test-dirs.txt");
	}

	/** 재개할 때 컴파일이 고쳐졌는지 보는 빌드 */
	public Path resumeCompileLog() {
		return this.dir.resolve("resume-compile.log");
	}

	public Path compileLog() {
		return this.dir.resolve("compile.log");
	}

	public Path buildLog() {
		return this.dir.resolve("build.log");
	}

	public Path testDirs() {
		return this.dir.resolve("test-dirs.txt");
	}

}
