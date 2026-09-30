package com.eottabom.migration.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * 한 번의 실행 설정. CLI 옵션과 설정 파일(spring-boot-migrator.yml)을 합친 값이다 ({@link ConfigLoader}).
 *
 * @param commit 게이트를 통과한 stage 마다 git commit
 * @param allowDirty 커밋되지 않은 변경이 있어도 시작한다
 */
public record MigrationConfig(Path projectDir, Target target, Mode mode, GateSettings gate, RecipeSettings recipes,
		BuildSettings build, boolean commit, boolean allowDirty) {

	public static final int DEFAULT_TEST_RETRIES = 1;

	public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(180);

	/** 설정 파일과 CLI 옵션이 없을 때의 값. schema/config.schema.json 의 default 와 같다 */
	public static MigrationConfig defaults(Path projectDir) {
		return new MigrationConfig(projectDir, new Target(null, JavaTarget.LATEST), Mode.STAGED,
				new GateSettings(GateLevel.BUILD, DEFAULT_TEST_RETRIES, true), new RecipeSettings(true, true),
				new BuildSettings(Jdk.AUTO, null, DEFAULT_TIMEOUT), false, false);
	}

	public boolean preview() {
		return this.mode == Mode.PREVIEW;
	}

	/** 목표까지 한 번에 적용한다 */
	public boolean allAtOnce() {
		return this.mode == Mode.ALL;
	}

	/** 실행 기록과 콘솔에 보여 주는 설정 요약. 기본값과 다른 것만 적는다. 예) gate=build, commit, java=21 */
	public String summary(@Nullable Integer targetJava) {
		List<String> options = new ArrayList<>(List.of("gate=" + this.gate.level().option()));
		addIf(options, this.mode != Mode.STAGED, "mode=" + this.mode.option());
		addIf(options, this.commit, "commit");
		addIf(options, targetJava != null, "java=" + targetJava);
		addIf(options, !this.recipes.custom(), "recipes.custom=false");
		addIf(options, !this.recipes.project(), "recipes.project=false");
		addIf(options, this.allowDirty, "allow-dirty");
		addIf(options, !this.gate.baselineTests(), "gate.baselineTests=false");
		addIf(options, this.gate.testRetries() != DEFAULT_TEST_RETRIES, "gate.testRetries=" + this.gate.testRetries());
		addIf(options, this.build.jdk() != Jdk.AUTO, "build.jdk=" + this.build.jdk().option());
		return String.join(", ", options);
	}

	private static void addIf(List<String> options, boolean enabled, String option) {
		if (enabled) {
			options.add(option);
		}
	}

	/**
	 * @param boot 목표 Boot. null 이면 guides/boot/ 의 마지막 버전
	 */
	public record Target(@Nullable String boot, JavaTarget java) {
	}

	/**
	 * @param testRetries stage 에서 새로 실패한 테스트를 다시 돌리는 횟수 (0 이면 다시 돌리지 않는다)
	 * @param baselineTests 원본 빌드에서도 테스트를 돌린다. false 면 원래 실패하던 테스트를 알 수 없어 stage 의 테스트 실패는
	 * 모두 새 실패로 본다
	 */
	public record GateSettings(GateLevel level, int testRetries, boolean baselineTests) {
	}

	/**
	 * @param custom custom 레시피를 붙인다. false 면 upstream 만 (비교용)
	 * @param project 대상 프로젝트의 .rewrite/ 레시피를 붙인다
	 */
	public record RecipeSettings(boolean custom, boolean project) {
	}

	/**
	 * @param jvmArgs 대상 Gradle 데몬 JVM 옵션. null 이면 장비 메모리 기준
	 * @param timeout 대상 Gradle 한 번 실행의 제한 시간. 0 이면 제한 없음
	 */
	public record BuildSettings(Jdk jdk, @Nullable String jvmArgs, Duration timeout) {

		/** JDK 자동 선택을 끄고 지금 JAVA_HOME 으로 대상 Gradle 을 띄운다 */
		public boolean usesCurrentJavaHome() {
			return this.jdk == Jdk.CURRENT;
		}

	}

	public enum Jdk {

		/** 프로젝트의 Java 버전에 맞는 JDK 를 찾아 쓴다 */
		AUTO,

		/** 지금 JAVA_HOME 그대로 */
		CURRENT;

		public static Jdk parse(String option) {
			return switch (option.trim().toLowerCase(Locale.ROOT)) {
				case "auto" -> AUTO;
				case "current" -> CURRENT;
				default -> throw new IllegalArgumentException("build.jdk 는 auto | current");
			};
		}

		public String option() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

}
