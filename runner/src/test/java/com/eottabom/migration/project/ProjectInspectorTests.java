package com.eottabom.migration.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import com.eottabom.migration.GitFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectInspectorTests {

	@TempDir
	Path dir;

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("buildLayouts")
	void readsVersionsFromBuildFiles(String scenario, Map<String, String> files, String boot, String gradle,
			Integer java, Integer toolchainJava) throws IOException {
		for (Map.Entry<String, String> file : files.entrySet()) {
			write(file.getKey(), file.getValue());
		}

		ProjectState model = new ProjectInspector().inspect(this.dir);

		assertThat(model.bootVersion()).isEqualTo(boot);
		assertThat(model.gradleVersion()).isEqualTo(gradle);
		assertThat(model.javaVersion()).isEqualTo(java);
		assertThat(model.toolchainJava()).isEqualTo(toolchainJava);
		assertThat(model.git()).isFalse();
	}

	// @formatter:off
	static Stream<Arguments> buildLayouts() {
		return Stream.of(
			Arguments.of(
				"Groovy DSL 플러그인 버전, 모듈별 Java, build 아래 파일은 무시",
				Map.of(
					"build.gradle", "plugins {\n    id 'org.springframework.boot' version '3.2.8' apply false\n}\n",
					"api/build.gradle", "java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\n",
					"batch/build.gradle", "sourceCompatibility = '17'\n",
					"build/generated/build.gradle", "sourceCompatibility = '11'\n",
					"gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n"),
				"3.2.8", "8.8", 17, 21
			),
			Arguments.of(
				"Kotlin DSL 플러그인 버전",
				Map.of("build.gradle.kts", "plugins {\n    id(\"org.springframework.boot\") version \"3.4.1\"\n}\n"),
				"3.4.1", null, null, null
			),
			Arguments.of(
				"gradle.properties 의 springBootVersion",
				Map.of("gradle.properties", "springBootVersion=2.7.18\n"),
				"2.7.18", null, null, null
			),
			Arguments.of(
				"buildscript classpath 의 Boot Gradle 플러그인",
				Map.of("build.gradle", "buildscript {\n    dependencies {\n        classpath 'org.springframework.boot:spring-boot-gradle-plugin:2.7.18'\n    }\n}\n"),
				"2.7.18", null, null, null
			),
			Arguments.of(
				"Kotlin DSL extra 속성",
				Map.of("build.gradle.kts", "extra[\"springBootVersion\"] = \"2.7.18\"\n"),
				"2.7.18", null, null, null
			),
			Arguments.of(
				"settings 의 pluginManagement, version 앞뒤 공백",
				Map.of("settings.gradle", "pluginManagement {\n    plugins {\n        id 'org.springframework.boot'  version  '3.3.5'\n    }\n}\n"),
				"3.3.5", null, null, null
			),
			Arguments.of(
				"주석 처리한 선언은 무시",
				Map.of("build.gradle", "plugins {\n    // id 'org.springframework.boot' version '2.7.0'\n    /* id 'org.springframework.boot' version '2.6.0' */\n    id 'org.springframework.boot' version '3.1.2'\n}\n"),
				"3.1.2", null, null, null
			),
			Arguments.of(
				"문자열 안의 /* 와 // 는 주석이 아니다",
				Map.of("build.gradle", "sourceSets { main { java { exclude 'com/demo/generated/**' } } }\n"
						+ "java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }\n"
						+ "def site = \"https://example.com\" // 주석 */\n"
						+ "test { exclude '**/*IT*' }\n"),
				null, null, 17, 17
			),
			Arguments.of(
				"version catalog 의 작은따옴표 값, 플러그인 축약 표기, 문자열 안의 #",
				Map.of(
					"build.gradle", "plugins {\n    alias(libs.plugins.spring.boot) apply false\n}\n",
					"gradle/libs.versions.toml", """
						[versions]
						java = '21'
						note = "a#b"

						[plugins]
						spring-boot = 'org.springframework.boot:3.4.5' # boot
						"""),
				"3.4.5", null, null, null
			),
			Arguments.of(
				"version catalog 의 Boot 플러그인과 Java",
				Map.of(
					"build.gradle", "plugins {\n    alias(libs.plugins.spring.boot) apply false\n}\n",
					"api/build.gradle", "java { toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInteger()) } }\n",
					"gradle/libs.versions.toml", """
						[versions]
						java = "25"
						spring-boot = "4.0.7"  # boot

						[plugins]
						spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
						"""),
				"4.0.7", null, 25, 25
			),
			Arguments.of(
				"이름이 libs 가 아닌 version catalog",
				Map.of(
					"build.gradle", "plugins {\n    alias(deps.plugins.spring.boot) apply false\n}\n",
					"api/build.gradle", "java { toolchain { languageVersion = JavaLanguageVersion.of(deps.versions.java.get().toInteger()) } }\n",
					"gradle/deps.versions.toml", """
						[versions]
						java = "21"
						spring-boot = "3.5.3"

						[plugins]
						spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
						"""),
				"3.5.3", null, 21, 21
			),
			Arguments.of(
				"루트에 없으면 서브프로젝트 선언 중 가장 낮은 버전",
				Map.of(
					"build.gradle", "plugins { id 'base' }\n",
					"a/build.gradle", "plugins { id 'org.springframework.boot' version '3.5.3' }\n",
					"b/build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.4.1\" }\n"),
				"3.4.1", null, null, null
			),
			Arguments.of(
				"Java 8 표기 (VERSION_1_8)",
				Map.of("build.gradle", "java { sourceCompatibility = JavaVersion.VERSION_1_8 }\n"),
				null, null, 8, 8
			)
		);
	}
	// @formatter:on

	private void write(String path, String content) throws IOException {
		Path file = this.dir.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	@Test
	void treatsRepositoryRootAndLinkedWorktreeAsGitButNotSubdirectory() {
		Path repo = this.dir.resolve("repo");
		GitFixture.write(repo.resolve("api/build.gradle"), "plugins {}\n");
		GitFixture.init(repo);
		Path worktree = this.dir.resolve("worktree");
		GitFixture.git(repo, "worktree", "add", "-q", "-b", "feature", worktree.toString());

		assertThat(ProjectInspector.isGitRoot(repo)).isTrue();
		assertThat(ProjectInspector.isGitRoot(worktree)).isTrue();
		assertThat(ProjectInspector.isGitRoot(repo.resolve("api"))).isFalse();
	}

}
