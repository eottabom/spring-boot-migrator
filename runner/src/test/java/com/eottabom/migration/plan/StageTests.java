package com.eottabom.migration.plan;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StageTests {

	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource({ "01-boot-3.4, 1", "12-java21, 12", "105-gradle8.14, 105" })
	void readsOrderFromTag(String tag, int expectedOrder) {
		assertThat(Stage.orderOf(tag)).isEqualTo(expectedOrder);
	}

	@ParameterizedTest(name = "[{index}] {0} {1} -> {3}")
	@CsvSource({ "BOOT, 3.4, 1, 01-boot-3.4, 3.4", "JAVA, java21, 7, 07-java21, java21",
			"GRADLE, gradle8.14, 120, 120-gradle8.14, gradle" })
	void tagRoundTripsOrderAndName(Stage.Kind kind, String name, int order, String expectedTag,
			String expectedProjectTag) {
		Stage stage = new Stage(kind, name, "recipe");
		String tag = stage.tag(order);

		assertThat(tag).isEqualTo(expectedTag);
		assertThat(Stage.orderOf(tag)).isEqualTo(order);
		assertThat(Stage.nameOf(tag)).isEqualTo(name);
		assertThat(Stage.projectTag(stage.name())).isEqualTo(expectedProjectTag);
		assertThat(stage.recipes()).containsExactly("recipe");
		assertThat(stage.covers()).containsExactly(name);
	}

}
