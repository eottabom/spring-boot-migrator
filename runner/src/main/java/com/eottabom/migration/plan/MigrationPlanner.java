package com.eottabom.migration.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import com.eottabom.migration.config.JavaTarget;
import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.guide.BootGuide;
import com.eottabom.migration.guide.BootRequirements;
import com.eottabom.migration.guide.BootRequirements.GradleSupport;
import com.eottabom.migration.guide.GradleGuide;
import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.guide.JavaGuide;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.version.Versions;
import org.jspecify.annotations.Nullable;

/**
 * 현재 버전과 목표로 실행할 stage 를 정한다. Java 는 기본으로 목표 Boot 가 지원하는 가장 높은 LTS 까지 올리고, 그 JDK 가 요구하는
 * Gradle 이 부족하면 Java stage 앞에 Gradle stage 를 넣는다. Gradle 은 목표 Boot 가 지원하면 그대로 두고 필요할 때만
 * 올린다. stage 레시피가 함께 올리는 Gradle, Java(가이드의 raises) 는 다음 판단에 반영한다.
 */
public record MigrationPlanner(Guides guides) {

	/** 이보다 낮은 Boot 는 3.0 stage 의 upstream 체인이 다루지 않는다 */
	static final String MINIMUM_BOOT = "2.5";

	/** upstream 과 custom 을 순서대로 묶은 stage 레시피 (recipes 의 META-INF/rewrite/stage/) */
	static final String STAGE_RECIPE = "com.eottabom.rewrite.stage.";

	static final String UPSTREAM_RECIPE = "com.eottabom.rewrite.upstream.";

	public MigrationPlan plan(ProjectState project, MigrationConfig config) {
		if (project.bootVersion() == null) {
			throw new IllegalArgumentException("빌드 파일에서 Spring Boot 버전을 찾지 못했어요 (root build.gradle 의 plugins 블록 확인). "
					+ "migrationRun, migrationScan 은 대상 Gradle 이 resolve 한 버전으로 다시 찾아요");
		}
		List<String> bootStages = this.guides.bootVersions();
		String target = (config.target().boot() != null) ? minor(config.target().boot())
				: bootStages.get(bootStages.size() - 1);
		if (!bootStages.contains(target)) {
			throw new IllegalArgumentException("목표 버전은 다음 중 하나: " + String.join(" ", bootStages));
		}
		String current = minor(project.bootVersion());
		if (compare(current, MINIMUM_BOOT) < 0) {
			throw new IllegalArgumentException(
					"Boot " + project.bootVersion() + " 에서는 시작할 수 없어요. Boot " + MINIMUM_BOOT + " 이상으로 먼저 올려 주세요");
		}
		BootRequirements targetRequirements = this.guides.boot(target).requirements();
		if (compare(current, target) > 0) {
			// 버전을 내리지 않는다. 목표보다 높은 Boot 에 낮은 Boot 기준의 Java stage 를 붙이지 않도록 빈 계획으로 끝낸다
			return new MigrationPlan(target, targetRequirements, null, List.of(), List.of());
		}
		Integer targetJava = targetJava(config.target().java(), target, targetRequirements);

		List<String> notes = new ArrayList<>();
		List<String> bootPath = new ArrayList<>();
		for (String stage : bootStages) {
			if (compare(current, stage) < 0 && compare(stage, target) <= 0) {
				bootPath.add(stage);
			}
		}
		List<Stage> plan = new ArrayList<>();
		String gradle = project.gradleVersion();
		int currentJava = (project.lowestDeclaredJava() != null) ? project.lowestDeclaredJava() : 0;
		for (String version : bootPath) {
			BootGuide guide = this.guides.boot(version);
			gradle = ensureGradle(plan, notes, gradle, raisedGradle(gradle, guide), guide);
			plan.add(bootStage(version, config.recipes().custom()));
			gradle = raisedGradle(gradle, guide);
			currentJava = raisedJava(currentJava, guide);
		}

		if (targetJava != null && currentJava < targetJava) {
			JavaGuide java = this.guides.java(targetJava);
			if (gradle != null && Versions.compare(gradle, java.gradle()) < 0) {
				GradleGuide upgrade = lowestGradle(java.gradle(), (version) -> true)
					.orElseThrow(() -> new IllegalArgumentException("Java " + java.version() + " 에 필요한 Gradle "
							+ java.gradle() + " 이상의 가이드가 없어요 (guides/gradle/)"));
				notes.add("Gradle " + gradle + " 는 JDK " + targetJava + " 위에서 뜨지 않아요 (" + java.gradle()
						+ "+ 필요) → Gradle " + upgrade.version() + " stage 추가");
				plan.add(gradleStage(upgrade));
				gradle = upgrade.version();
				if (targetRequirements.gradleSupport(gradle) == GradleSupport.NOT_LISTED) {
					notes.add("Gradle " + gradle + " 는 Boot " + target + " 공식 지원 목록(" + targetRequirements.gradleRange()
							+ ")에 없어요. 동작은 하지만 문제가 생기면 이것부터 확인해 주세요");
				}
			}
			plan.add(new Stage(Stage.Kind.JAVA, "java" + targetJava, STAGE_RECIPE + "Java_" + targetJava));
		}
		javaNotes(notes, config.target().java(), project.lowestDeclaredJava(), targetJava, target, targetRequirements);
		List<Stage> stages = (config.allAtOnce() && plan.size() > 1) ? List.of(allAtOnce(plan)) : List.copyOf(plan);
		return new MigrationPlan(target, targetRequirements, targetJava, stages, List.copyOf(notes));
	}

	/** stage 레시피가 올린 뒤의 Gradle (낮은 버전은 올리고, 높은 버전은 내리지 않는다) */
	private static @Nullable String raisedGradle(@Nullable String gradle, BootGuide guide) {
		String raises = (guide.raises() != null) ? guide.raises().gradle() : null;
		return (gradle != null && raises != null && Versions.compare(gradle, raises) < 0) ? raises : gradle;
	}

	private static int raisedJava(int java, BootGuide guide) {
		Integer raises = (guide.raises() != null) ? guide.raises().java() : null;
		return (raises != null) ? Math.max(java, raises) : java;
	}

	/** --mode=all. 모든 stage 의 레시피를 차례로 이어 한 번에 돌린다. 이름은 마지막 Boot stage */
	private static Stage allAtOnce(List<Stage> stages) {
		Stage last = stages.stream()
			.filter((stage) -> stage.kind() == Stage.Kind.BOOT)
			.reduce((first, second) -> second)
			.orElse(stages.get(stages.size() - 1));
		return new Stage(last.kind(), last.name(),
				stages.stream().flatMap((stage) -> stage.recipes().stream()).toList(),
				stages.stream().map(Stage::name).toList());
	}

	/**
	 * Boot stage 전에 Gradle 이 그 stage 의 지원 범위보다 낮으면 지원 범위에 드는 가장 낮은 Gradle stage 를 넣는다.
	 * stage 레시피가 스스로 올리는 버전(raised)으로 충분하면 넣지 않는다. 올린 뒤의 Gradle 버전을 돌려준다.
	 */
	private @Nullable String ensureGradle(List<Stage> plan, List<String> notes, @Nullable String gradle,
			@Nullable String raised, BootGuide guide) {
		if (gradle == null) {
			return null;
		}
		BootRequirements requirements = guide.requirements();
		GradleSupport support = requirements.gradleSupport(Objects.requireNonNull(raised));
		if (support == GradleSupport.TOO_OLD) {
			GradleGuide upgrade = lowestGradle(gradle,
					(version) -> requirements.gradleSupport(version) == GradleSupport.SUPPORTED)
				.orElseThrow(() -> new IllegalArgumentException("Boot " + guide.version() + " 은 Gradle "
						+ requirements.gradleRange() + " 가 필요한데 맞는 Gradle 가이드가 없어요 (guides/gradle/)"));
			notes.add("Gradle " + gradle + " 는 Boot " + guide.version() + " 지원 범위(" + requirements.gradleRange()
					+ ") 밖 → Gradle " + upgrade.version() + " stage 추가");
			plan.add(gradleStage(upgrade));
			return upgrade.version();
		}
		if (support == GradleSupport.NOT_LISTED) {
			String note = "Gradle " + raised + " 는 Boot " + guide.version() + " 공식 지원 목록(" + requirements.gradleRange()
					+ ")에 없어요. 동작은 하지만 문제가 생기면 이것부터 확인해 주세요";
			if (!notes.contains(note)) {
				notes.add(note);
			}
		}
		return gradle;
	}

	/** minimum 이상이고 조건에 맞는 가장 낮은 Gradle 가이드 */
	private Optional<GradleGuide> lowestGradle(String minimum, Predicate<String> condition) {
		return this.guides.gradle()
			.stream()
			.filter((guide) -> Versions.compare(guide.version(), minimum) >= 0 && condition.test(guide.version()))
			.findFirst();
	}

	/** latest(기본)는 목표 Boot 가 지원하는 가장 높은 LTS, keep 은 지원하면 유지, 숫자는 그 버전, none 은 올리지 않는다 */
	private @Nullable Integer targetJava(JavaTarget option, String target, BootRequirements requirements) {
		return switch (option.kind()) {
			case KEEP, NONE -> null;
			case LATEST -> this.guides.javaVersions()
				.stream()
				.filter(requirements::supportsJava)
				.max(Integer::compare)
				.orElse(requirements.java().min());
			case VERSION -> {
				int version = Objects.requireNonNull(option.version());
				this.guides.java(version);
				if (!requirements.supportsJava(version)) {
					throw new IllegalArgumentException("Java " + version + " 는 Boot " + target + " 지원 범위("
							+ requirements.java().min() + " ~ " + requirements.java().max() + ") 밖이에요");
				}
				yield version;
			}
		};
	}

	private static void javaNotes(List<String> notes, JavaTarget option, @Nullable Integer currentJava,
			@Nullable Integer targetJava, String target, BootRequirements requirements) {
		if (targetJava != null || currentJava == null) {
			return;
		}
		int min = requirements.java().min();
		int max = requirements.java().max();
		if (currentJava < min) {
			notes.add("Java " + currentJava + " → " + min + ": Boot 3.0 stage 레시피가 최소 요구 버전으로 맞춰요");
		}
		else if (currentJava > max) {
			notes.add("Java " + currentJava + " 는 Boot " + target + " 가 검증한 범위(" + min + " ~ " + max + ")보다 높아요");
		}
		else if (option.kind() != JavaTarget.Kind.NONE) {
			notes.add("Java " + currentJava + " 는 Boot " + target + " 지원 범위(" + min + " ~ " + max
					+ ") 안이라 그대로 둬요 (--java=keep. 올리려면 --java=latest)");
		}
	}

	private static Stage gradleStage(GradleGuide guide) {
		return new Stage(Stage.Kind.GRADLE, "gradle" + guide.version(),
				STAGE_RECIPE + "Gradle_" + guide.version().replace('.', '_'));
	}

	/** upstream 만 쓰면 upstream stage 와 그 버전 변경을 옮긴 catalog 규칙만 돌린다 */
	private static Stage bootStage(String version, boolean custom) {
		String suffix = version.replace('.', '_');
		List<String> recipes = custom ? List.of(STAGE_RECIPE + "Boot_" + suffix)
				: List.of(UPSTREAM_RECIPE + "Boot_" + suffix, UPSTREAM_RECIPE + "catalog.Boot_" + suffix);
		return new Stage(Stage.Kind.BOOT, version, recipes, List.of(version));
	}

	static String minor(String version) {
		String[] parts = version.split("\\.");
		return (parts.length >= 2) ? parts[0] + "." + parts[1] : version;
	}

	static int compare(String a, String b) {
		return Versions.compare(minor(a), minor(b));
	}

}
