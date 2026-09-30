package com.eottabom.migration.guide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.eottabom.migration.guide.ChecklistItem.Fix;
import com.eottabom.migration.stage.StageId;
import com.eottabom.migration.version.ResolvedVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuidesTests {

	static final Path GUIDES = Path.of("../guides");

	static final Path SCHEMA = Path.of("../schema");

	private final Guides guides = Guides.load(GUIDES, SCHEMA);

	@Test
	void loadsEveryStageGuideInVersionOrder() {
		assertThat(this.guides.bootVersions()).containsExactly("3.0", "3.1", "3.2", "3.3", "3.4", "3.5", "4.0", "4.1");
		assertThat(this.guides.javaVersions()).containsExactly(17, 21, 25);
		assertThat(this.guides.gradle()).extracting(GradleGuide::version).containsExactly("8.14", "9.1");
		assertThat(this.guides.boot("3.0").raises()).isEqualTo(new BootGuide.Raises("7.6.4", 17));
		assertThat(this.guides.boot("3.4").requirements().gradleRange()).isEqualTo("7.6.4+ / 8.4+");
		assertThat(this.guides.java(25).gradle()).isEqualTo("9.1");
	}

	@Test
	void fillsDefaultsFromGuide() {
		ChecklistItem item = checklistItem("3.0", "boot-3.0/trailing-slash");

		assertThat(item.fix()).isEqualTo(Fix.MANUAL);
		assertThat(item.source()).isEqualTo(this.guides.boot("3.0").source());
		assertThat(checklistItem("3.4", "boot-3.4/mockbean").fix()).isEqualTo(Fix.AUTO);
		assertThat(checklistItem("3.4", "boot-3.4/spy-caching-proxy").detect())
			.isEqualTo("com.eottabom.rewrite.detect.testing.FindSpyStubbingThroughCachingProxy");
	}

	@ParameterizedTest(name = "[{index}] {0} {1} -> {2}")
	@MethodSource("dependencyConditions")
	void checklistItemWithDependencyConditionMatchesOnlyWhenDependencyPresent(String stage,
			Map<String, String> dependencies, String expected, List<String> unexpected) {
		List<String> ids = ids(this.guides.checklist(List.of(StageId.parse(stage)), new ResolvedVersions(dependencies),
				new ResolvedVersions(dependencies)));

		assertThat(ids).contains(expected);
		unexpected.forEach((id) -> assertThat(ids).doesNotContain(id));
	}

	// @formatter:off
	static Stream<Arguments> dependencyConditions() {
		return Stream.of(
			Arguments.of(
				"4.0",
				Map.of("org.springframework.boot:spring-boot", "4.0.0"),
				"boot-4.0/jackson3",
				List.of("boot-4.0/mongodb-properties")
			),
			Arguments.of(
				"4.0",
				Map.of("org.mongodb:mongodb-driver-sync", "5.5.0"),
				"boot-4.0/mongodb-properties",
				List.of()
			)
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] {0} ({1} -> {2}) -> {3}")
	@MethodSource("libraryChanges")
	void libraryChecklistMatchesByCrossesAndAffected(String stage, String before, String after, String expected,
			String expectedTrigger, List<String> unexpected) {
		String library = "org.hibernate.orm:hibernate-core";
		List<ChecklistMatch> matches = this.guides.checklist(List.of(StageId.parse(stage)),
				new ResolvedVersions(Map.of(library, before)), new ResolvedVersions(Map.of(library, after)));

		assertThat(ids(matches)).contains(expected);
		unexpected.forEach((id) -> assertThat(ids(matches)).doesNotContain(id));
		assertThat(matches.stream()
			.filter((match) -> match.item().id().equals(expected))
			.findFirst()
			.orElseThrow()
			.trigger()).isEqualTo(expectedTrigger);
	}

	// @formatter:off
	static Stream<Arguments> libraryChanges() {
		return Stream.of(
			Arguments.of(
				"3.4", "6.5.2.Final", "6.6.4.Final",
				"libraries-hibernate-core/hibernate-66",
				"`org.hibernate.orm:hibernate-core` 6.5.2.Final → 6.6.4.Final",
				List.of("libraries-hibernate-core/hibernate-hhh18378", "libraries-hibernate-core/hibernate-7")
			),
			Arguments.of(
				"3.3", "6.4.4.Final", "6.5.2.Final",
				"libraries-hibernate-core/hibernate-hhh18378",
				"`org.hibernate.orm:hibernate-core` 6.4.4.Final → 6.5.2.Final",
				List.of()
			)
		);
	}
	// @formatter:on

	@Test
	void doesNotAssumeUnknownDependencies() {
		assertThat(this.guides.stage(StageId.parse("3.5")).checklist()).extracting(ChecklistItem::id)
			.contains("boot-3.5/task-executor-name", "boot-3.5/heapdump");
		assertThat(ids(this.guides.checklist(List.of(StageId.boot("3.5")), ResolvedVersions.UNKNOWN,
				ResolvedVersions.UNKNOWN)))
			.contains("boot-3.5/task-executor-name")
			.doesNotContain("boot-3.5/heapdump");
	}

	@Test
	void stageHintsComeBeforeCommonHints() {
		List<FailureHint> hints = this.guides.failureHints(List.of(StageId.boot("3.5")));

		assertThat(hints.get(0).text()).contains("taskExecutor");
		assertThat(hints).extracting(FailureHint::text)
			.anyMatch((text) -> text.contains("Docker"))
			.noneMatch((text) -> text.contains("MockMvc"));
		assertThat(this.guides.deprecations(List.of(StageId.boot("3.5")))).extracting(Deprecation::recipe)
			.contains("org.openrewrite.java.migrate.util.UseLocaleOf");
		assertThat(this.guides.deprecations(List.of(StageId.boot("3.5"))))
			.filteredOn((deprecation) -> deprecation.matches("Locale(String) in Locale has been deprecated"))
			.hasSize(1);
	}

	@Test
	void resolvesStageNames() {
		assertThat(this.guides.stage(StageId.parse("java21"))).isInstanceOf(JavaGuide.class);
		assertThat(this.guides.stage(StageId.parse("gradle9.1"))).isInstanceOf(GradleGuide.class);
		assertThatThrownBy(() -> this.guides.stage(StageId.parse("gradle7.0")))
			.hasMessageContaining("guides/gradle/7.0.yml");
		assertThatThrownBy(() -> this.guides.java(11)).hasMessageContaining("--java 는 17 | 21 | 25");
		assertThatThrownBy(() -> this.guides.boot("2.7")).hasMessageContaining("guides/boot/2.7.yml");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("invalidGuides")
	void rejectsGuidesThatDoNotMatchSchema(String scenario, String content, String expectedMessage, @TempDir Path dir)
			throws IOException {
		Files.createDirectories(dir.resolve("gradle"));
		Files.writeString(dir.resolve("gradle/9.1.yml"), content);

		assertThatThrownBy(() -> Guides.load(dir, SCHEMA)).hasMessageContaining("gradle-guide.schema.json")
			.hasMessageContaining(expectedMessage);
	}

	// @formatter:off
	static Stream<Arguments> invalidGuides() {
		return Stream.of(
			Arguments.of("source 가 없다", "checklist: []\n", "source"),
			Arguments.of("fix: auto 에 recipe 가 없다",
					"source: https://a\nchecklist:\n  - { id: a, title: t, fix: auto }\n", "recipe"),
			Arguments.of("모르는 필드",
					"source: https://a\nchecklist:\n  - { id: a, title: t, mode: REPORT_ONLY }\n", "mode"),
			Arguments.of("빈 파일", "", "source")
		);
	}
	// @formatter:on

	@Test
	void rejectsDuplicateChecklistIds(@TempDir Path dir) throws IOException {
		Files.createDirectories(dir.resolve("gradle"));
		Files.writeString(dir.resolve("gradle/9.1.yml"),
				"source: https://a\nchecklist:\n  - { id: a, title: t }\n  - { id: a, title: u }\n");

		assertThatThrownBy(() -> Guides.load(dir, SCHEMA)).hasMessageContaining("gradle-9.1/a");
	}

	@Test
	void loadsWithoutOptionalDirectories(@TempDir Path dir) {
		Guides empty = Guides.load(dir, SCHEMA);

		assertThat(empty.bootVersions()).isEmpty();
		assertThat(empty.common()).isEqualTo(CommonGuide.EMPTY);
	}

	private ChecklistItem checklistItem(String stage, String id) {
		return this.guides.stage(StageId.parse(stage))
			.checklist()
			.stream()
			.filter((item) -> item.id().equals(id))
			.findFirst()
			.orElseThrow();
	}

	private static List<String> ids(List<ChecklistMatch> matches) {
		return matches.stream().map((match) -> match.item().id()).toList();
	}

}
