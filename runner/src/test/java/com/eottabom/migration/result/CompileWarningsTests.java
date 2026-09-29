package com.eottabom.migration.result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CompileWarningsTests {

	@TempDir
	Path project;

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', value = {
			"프로젝트 안의 경로는 상대 경로로|{project}/src/A.java:3: warning: [removal] old() in A has been deprecated|removal|src/A.java:3",
			"공백이 있는 경로|{project}/my module/B.java:7: warning: [deprecation] run() in B has been deprecated|deprecation|my module/B.java:7",
			"한국어 로케일|{project}/src/A.java:3: 경고: [removal] old() in A has been deprecated|removal|src/A.java:3",
			"Windows 경로는 슬래시로|C:\\work\\api\\src\\C.java:9: warning: [removal] gone() in C has been deprecated|removal|C:/work/api/src/C.java:9" })
	void collectsWarningLocations(String scenario, String line, String kind, String expectedLocation)
			throws IOException {
		Path log = this.project.resolve("compile.log");
		Files.writeString(log, line.replace("{project}", this.project.toAbsolutePath().toString()) + "\n");

		CompileWarnings warnings = CompileWarnings.collect(log, this.project);

		assertThat(kind.equals("removal") ? warnings.removal() : warnings.deprecation()).singleElement()
			.satisfies((warning) -> assertThat(warning.locations()).containsExactly(expectedLocation));
	}

}
