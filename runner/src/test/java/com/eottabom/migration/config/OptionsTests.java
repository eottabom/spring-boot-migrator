package com.eottabom.migration.config;

import com.eottabom.migration.config.MigrationConfig.Jdk;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptionsTests {

	@ParameterizedTest(name = "[{index}] --gate={0} -> {1}")
	@CsvSource({ "compile, COMPILE, true, false", "build, BUILD, true, true", "none, NONE, false, false",
			"NONE, NONE, false, false" })
	void parsesGate(String option, GateLevel expected, boolean compiles, boolean builds) {
		GateLevel gate = GateLevel.parse(option);

		assertThat(gate).isEqualTo(expected);
		assertThat(gate.compiles()).isEqualTo(compiles);
		assertThat(gate.builds()).isEqualTo(builds);
		assertThat(gate.option()).isEqualTo(expected.name().toLowerCase());
	}

	@ParameterizedTest(name = "[{index}] --java={0} -> {1} {2}")
	@CsvSource(nullValues = "null",
			value = { "keep, KEEP, null, keep", "latest, LATEST, null, latest", "21, VERSION, 21, 21" })
	void parsesJavaTarget(String option, JavaTarget.Kind kind, Integer version, String display) {
		JavaTarget java = JavaTarget.parse(option);

		assertThat(java.kind()).isEqualTo(kind);
		assertThat(java.version()).isEqualTo(version);
		assertThat(java).hasToString(display);
	}

	@ParameterizedTest(name = "[{index}] --mode={0} -> {1}")
	@CsvSource({ "staged, STAGED", "ALL, ALL", "preview, PREVIEW" })
	void parsesMode(String option, Mode expected) {
		assertThat(Mode.parse(option)).isEqualTo(expected);
		assertThat(expected.option()).isEqualTo(expected.name().toLowerCase());
	}

	@ParameterizedTest(name = "[{index}] build.jdk={0} -> {1}")
	@CsvSource({ "auto, AUTO", "Current, CURRENT" })
	void parsesJdk(String option, Jdk expected) {
		assertThat(Jdk.parse(option)).isEqualTo(expected);
		assertThat(expected.option()).isEqualTo(expected.name().toLowerCase());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "stage", "", "dry-run" })
	void rejectsUnknownMode(String option) {
		assertThatThrownBy(() -> Mode.parse(option)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("--mode")
			.hasMessageContaining("staged | all | preview");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "latest", "", "17" })
	void rejectsUnknownJdk(String option) {
		assertThatThrownBy(() -> Jdk.parse(option)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("build.jdk");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "fast", "", "test" })
	void rejectsUnknownGate(String option) {
		assertThatThrownBy(() -> GateLevel.parse(option)).hasMessageContaining("--gate");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "java21", "", "newest", "auto", "none" })
	void rejectsUnknownJavaTarget(String option) {
		assertThatThrownBy(() -> JavaTarget.parse(option)).hasMessageContaining("--java");
	}

}
