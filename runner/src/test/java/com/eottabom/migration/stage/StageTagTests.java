package com.eottabom.migration.stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StageTagTests {

	@ParameterizedTest(name = "[{index}] {0} {1} #{2} -> {3}")
	@CsvSource({ "BOOT, 3.4, 1, 01-boot-3.4, 3.4, Spring Boot 3.4, 01_boot_3_4",
			"JAVA, 21, 7, 07-java21, java21, Java 21, 07_java21",
			"GRADLE, 8.14, 120, 120-gradle8.14, gradle8.14, Gradle 8.14, 120_gradle8_14" })
	void dirNameRoundTripsOrderAndStage(StageId.Kind kind, String version, int order, String expectedDirName,
			String expectedName, String expectedTitle, String expectedRecipeSuffix) {
		StageId stage = new StageId(kind, version);
		StageTag tag = new StageTag(order, stage);

		assertThat(tag.dirName()).isEqualTo(expectedDirName);
		assertThat(tag).hasToString(expectedDirName);
		assertThat(tag.recipeSuffix()).isEqualTo(expectedRecipeSuffix);
		assertThat(StageTag.parse(expectedDirName)).isEqualTo(tag);
		assertThat(stage.name()).isEqualTo(expectedName);
		assertThat(stage).hasToString(expectedName);
		assertThat(stage.title()).isEqualTo(expectedTitle);
		assertThat(StageId.parse(expectedName)).isEqualTo(stage);
	}

	@Test
	void factoriesBuildTheSameIdsAsParsing() {
		assertThat(StageId.boot("4.0")).isEqualTo(StageId.parse("4.0"));
		assertThat(StageId.java(25)).isEqualTo(StageId.parse("java25"));
		assertThat(StageId.gradle("9.1")).isEqualTo(StageId.parse("gradle9.1"));
	}

	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource({ "01-boot-3.4, true", "105-gradle8.14, true", "start, false", "scan, false", "1-boot-3.4, false" })
	void recognizesStageDirNames(String name, boolean expected) {
		assertThat(StageTag.isDirName(name)).isEqualTo(expected);
	}

	@Test
	void rejectsNamesThatAreNotStageDirs() {
		assertThatThrownBy(() -> StageTag.parse("start")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("start");
	}

}
