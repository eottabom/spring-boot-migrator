package com.eottabom.migration.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicFilesTests {

	@TempDir
	Path dir;

	@Test
	void replacesContentWithoutLeavingTemporaryFiles() throws IOException {
		Path file = this.dir.resolve("nested/state/.resume");

		AtomicFiles.write(file, "R_STAGE=3.4\n");
		AtomicFiles.write(file, "R_STAGE=3.5\n");

		assertThat(Files.readString(file)).isEqualTo("R_STAGE=3.5\n");
		try (Stream<Path> files = Files.list(file.getParent())) {
			assertThat(files).containsExactly(file);
		}
	}

}
