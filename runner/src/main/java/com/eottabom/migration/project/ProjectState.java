package com.eottabom.migration.project;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * 대상 프로젝트의 현재 상태. 찾지 못한 버전은 null.
 *
 * @param lowestDeclaredJava 빌드 파일에 선언된 Java 버전 중 가장 낮은 값 (마이그레이션 기준)
 * @param highestToolchainJava 선언된 toolchain / VERSION_NN 중 가장 높은 값 (대상 Gradle 을 띄울 JDK
 * 선택용)
 * @param dirty 커밋되지 않은 변경이 있다 (git 이 아니면 false)
 */
public record ProjectState(Path dir, @Nullable String bootVersion, @Nullable String gradleVersion,
		@Nullable Integer lowestDeclaredJava, @Nullable Integer highestToolchainJava, boolean gitRoot, boolean dirty) {

	public ProjectState withBootVersion(String bootVersion) {
		return new ProjectState(this.dir, bootVersion, this.gradleVersion, this.lowestDeclaredJava,
				this.highestToolchainJava, this.gitRoot, this.dirty);
	}

}
