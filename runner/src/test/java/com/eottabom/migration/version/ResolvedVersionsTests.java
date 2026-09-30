package com.eottabom.migration.version;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ResolvedVersionsTests {

	@TempDir
	Path dir;

	@Test
	void readsModuleVersionsAndSkipsLinesWithoutSeparator() throws IOException {
		Path file = this.dir.resolve("versions.txt");
		Files.writeString(file,
				"org.hibernate.orm:hibernate-core=6.6.4.Final\n\nnot a version line\ncom.example:lib=1.0\n");

		ResolvedVersions versions = ResolvedVersions.read(file);

		assertThat(versions.isUnknown()).isFalse();
		assertThat(versions.modules()).containsExactly("org.hibernate.orm:hibernate-core", "com.example:lib");
		assertThat(versions.of("org.hibernate.orm:hibernate-core")).isEqualTo("6.6.4.Final");
		assertThat(versions.of("org.missing:lib")).isNull();
	}

	@Test
	void missingFileMeansUnknownVersions() {
		ResolvedVersions versions = ResolvedVersions.read(this.dir.resolve("missing.txt"));

		assertThat(versions).isEqualTo(ResolvedVersions.UNKNOWN);
		assertThat(versions.isUnknown()).isTrue();
		assertThat(versions.modules()).isEmpty();
	}

}
