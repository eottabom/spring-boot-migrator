package com.eottabom.migration.guide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eottabom.migration.stage.StageId;

/**
 * guides/ 전체. 파일이 있는 버전이 stage 가 된다.
 *
 * <pre>
 * guides/
 *   boot/3.0.yml ... 4.1.yml     지원 범위, 체크리스트, 실패 힌트
 *   java/17.yml 21.yml 25.yml    LTS 만
 *   gradle/8.14.yml 9.1.yml
 *   libraries/*.yml              라이브러리 버전이 바뀔 때 거는 항목
 *   common.yml                   모든 stage 에 쓰는 실패 힌트
 * </pre>
 */
public record Guides(List<BootGuide> boot, List<JavaGuide> java, List<GradleGuide> gradle, List<LibraryGuide> libraries,
		CommonGuide common) {

	public static Guides load(Path guidesDir, Path schemaDir) {
		GuideReader reader = new GuideReader(schemaDir);
		Path common = guidesDir.resolve("common.yml");
		Guides guides = new Guides(reader.readAll(guidesDir.resolve("boot"), "boot-guide", BootGuide.class),
				reader.readAll(guidesDir.resolve("java"), "java-guide", JavaGuide.class),
				reader.readAll(guidesDir.resolve("gradle"), "gradle-guide", GradleGuide.class),
				reader.readAll(guidesDir.resolve("libraries"), "library-guide", LibraryGuide.class),
				Files.exists(common) ? reader.read(common, "common-guide", CommonGuide.class, "common")
						: CommonGuide.EMPTY);
		guides.checkUniqueIds();
		return guides;
	}

	/** 러너가 아는 Boot stage (버전 순서) */
	public List<String> bootVersions() {
		return this.boot.stream().map(BootGuide::version).toList();
	}

	public BootGuide boot(String version) {
		return this.boot.stream()
			.filter((guide) -> guide.version().equals(version))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("guides/boot/" + version + ".yml 이 없어요"));
	}

	/** Java stage 로 올릴 수 있는 버전 (LTS) */
	public List<Integer> javaVersions() {
		return this.java.stream().map(JavaGuide::version).toList();
	}

	public JavaGuide java(int version) {
		return this.java.stream()
			.filter((guide) -> guide.version() == version)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("--java 는 "
					+ String.join(" | ", javaVersions().stream().map(String::valueOf).toList()) + " | latest | keep"));
	}

	public GradleGuide gradle(String version) {
		return this.gradle.stream()
			.filter((guide) -> guide.version().equals(version))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("guides/gradle/" + version + ".yml 이 없어요"));
	}

	public StageGuide stage(StageId stage) {
		return switch (stage.kind()) {
			case BOOT -> boot(stage.version());
			case JAVA -> java(Integer.parseInt(stage.version()));
			case GRADLE -> gradle(stage.version());
		};
	}

	/**
	 * stage 들에 걸리는 체크리스트. stage 항목은 when 조건을 전후 의존성으로 보고, 라이브러리 항목은 전후 버전 변화로 본다.
	 * @param before stage 전 resolve 된 버전 (group:artifact → version). 모르면 빈 맵
	 * @param after stage 후 resolve 된 버전. 모르면 빈 맵
	 */
	public List<ChecklistMatch> checklist(List<StageId> stages, Map<String, String> before, Map<String, String> after) {
		Set<String> dependencies = new HashSet<>(before.keySet());
		dependencies.addAll(after.keySet());
		List<ChecklistMatch> matches = new ArrayList<>();
		for (StageId stage : stages) {
			for (ChecklistItem item : stage(stage).checklist()) {
				if (item.appliesTo(dependencies)) {
					matches.add(new ChecklistMatch(item, null));
				}
			}
		}
		for (LibraryGuide library : this.libraries) {
			String from = before.get(library.library());
			String to = after.get(library.library());
			if (from == null || to == null) {
				continue;
			}
			for (ChecklistItem item : library.checklist()) {
				if (item.triggeredBy(from, to)) {
					matches.add(new ChecklistMatch(item, "`" + library.library() + "` " + from + " → " + to));
				}
			}
		}
		return matches;
	}

	/** stage 들의 실패 힌트 다음에 공통 힌트. 위에서부터 처음 맞는 것 하나를 쓴다 */
	public List<FailureHint> failureHints(List<StageId> stages) {
		List<FailureHint> hints = new ArrayList<>();
		stages.forEach((stage) -> hints.addAll(stage(stage).failureHints()));
		hints.addAll(this.common.failureHints());
		return hints;
	}

	/** stage 들의 deprecated API 대체 레시피 다음에 공통 대체 레시피 */
	public List<Deprecation> deprecations(List<StageId> stages) {
		List<Deprecation> deprecations = new ArrayList<>();
		stages.forEach((stage) -> deprecations.addAll(stage(stage).deprecations()));
		deprecations.addAll(this.common.deprecations());
		return deprecations;
	}

	private void checkUniqueIds() {
		Set<String> ids = new HashSet<>();
		List<StageGuide> stages = new ArrayList<>(this.boot);
		stages.addAll(this.java);
		stages.addAll(this.gradle);
		List<ChecklistItem> items = new ArrayList<>();
		stages.forEach((guide) -> items.addAll(guide.checklist()));
		this.libraries.forEach((library) -> items.addAll(library.checklist()));
		for (ChecklistItem item : items) {
			if (!ids.add(item.id())) {
				throw new IllegalArgumentException("guides/ 에 체크리스트 id 가 중복돼요: " + item.id());
			}
		}
	}

}
