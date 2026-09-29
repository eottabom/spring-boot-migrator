package com.eottabom.migration.plan;

import java.util.List;

/**
 * 마이그레이션 stage 하나. stage 마다 rewriteRun 과 게이트를 한 번씩 돈다.
 *
 * @param name Boot stage 는 "3.4", Java stage 는 "java21", Gradle stage 는 "gradle8.14".
 * guides/ 의 파일과 짝이다
 * @param recipes 차례로 실행할 레시피 (stage.Boot_3_4 등)
 * @param covers 이 stage 가 다루는 stage 이름. 보통은 자기 이름 하나, --mode=all 이면 목표까지의 모든 stage
 */
public record Stage(Kind kind, String name, List<String> recipes, List<String> covers) {

	/** 레시피 하나로 도는 보통 stage */
	public Stage(Kind kind, String name, String recipe) {
		this(kind, name, List.of(recipe), List.of(name));
	}

	public enum Kind {

		BOOT, JAVA, GRADLE

	}

	/** 결과 파일 이름 접두사. 예) 03-boot-3.4, 06-java25, 02-gradle8.14 */
	public String tag(int order) {
		return String.format("%02d-%s", order, (this.kind == Kind.BOOT) ? "boot-" + this.name : this.name);
	}

	/** 콘솔과 커밋 메시지에 쓰는 레시피 목록 */
	public String recipeNames() {
		return String.join(", ", this.recipes);
	}

	/** tag(order) 로 만든 태그의 stage 번호 */
	public static int orderOf(String tag) {
		return Integer.parseInt(tag.substring(0, tag.indexOf('-')));
	}

	/** tag(order) 로 만든 태그의 stage 이름 (재개할 때는 stage 대신 태그만 남아 있다) */
	public static String nameOf(String tag) {
		String name = tag.substring(tag.indexOf('-') + 1);
		return name.startsWith("boot-") ? name.substring("boot-".length()) : name;
	}

	/** 프로젝트 레시피 태그(migration-stage:) 의 값. Gradle stage 는 버전과 무관하게 gradle */
	public static String projectTag(String stageName) {
		return stageName.startsWith("gradle") ? "gradle" : stageName;
	}

}
