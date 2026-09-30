package com.eottabom.migration.stage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * stage 가 무엇을 어느 버전으로 올리는가. guides/ 의 파일 하나와 짝이다.
 *
 * @param version Boot 는 "3.4", Java 는 "21", Gradle 은 "8.14"
 */
public record StageId(Kind kind, String version) {

	private static final String JAVA_PREFIX = "java";

	private static final String GRADLE_PREFIX = "gradle";

	public static StageId boot(String version) {
		return new StageId(Kind.BOOT, version);
	}

	public static StageId java(int version) {
		return new StageId(Kind.JAVA, String.valueOf(version));
	}

	public static StageId gradle(String version) {
		return new StageId(Kind.GRADLE, version);
	}

	/** {@link #name()} 으로 쓴 이름을 되읽는다 */
	@JsonCreator
	public static StageId parse(String name) {
		if (name.startsWith(JAVA_PREFIX)) {
			return new StageId(Kind.JAVA, name.substring(JAVA_PREFIX.length()));
		}
		if (name.startsWith(GRADLE_PREFIX)) {
			return new StageId(Kind.GRADLE, name.substring(GRADLE_PREFIX.length()));
		}
		return new StageId(Kind.BOOT, name);
	}

	/** 콘솔, 기록, 결과 파일에 쓰는 이름. Boot 는 "3.4", Java 는 "java21", Gradle 은 "gradle8.14" */
	@JsonValue
	public String name() {
		return switch (this.kind) {
			case BOOT -> this.version;
			case JAVA -> JAVA_PREFIX + this.version;
			case GRADLE -> GRADLE_PREFIX + this.version;
		};
	}

	/** 결과 제목과 커밋 메시지에 쓰는 이름. 예) Spring Boot 3.4, Java 21, Gradle 8.14 */
	public String title() {
		return switch (this.kind) {
			case BOOT -> "Spring Boot " + this.version;
			case JAVA -> "Java " + this.version;
			case GRADLE -> "Gradle " + this.version;
		};
	}

	@Override
	public String toString() {
		return name();
	}

	public enum Kind {

		BOOT, JAVA, GRADLE

	}

}
