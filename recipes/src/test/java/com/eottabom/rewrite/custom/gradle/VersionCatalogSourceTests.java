package com.eottabom.rewrite.custom.gradle;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.toml.TomlParser;

import static org.assertj.core.api.Assertions.assertThat;

class VersionCatalogSourceTests {

	@Test
	void keepsOriginalFileWhenNewTextCannotBeParsed() {
		InMemoryExecutionContext ctx = new InMemoryExecutionContext();
		SourceFile catalog = new TomlParser().parse(ctx, "[versions]\nboot = \"4.0.7\"\n")
			.findFirst()
			.orElseThrow()
			.withSourcePath(Path.of("gradle/libs.versions.toml"));

		assertThat(VersionCatalogSource.withText(catalog, "[versions\nboot = ", ctx)).isSameAs(catalog);
		assertThat(VersionCatalogSource.withText(catalog, "[versions]\nboot = \"4.1.1\"\n", ctx).printAll())
			.contains("4.1.1");
	}

}
