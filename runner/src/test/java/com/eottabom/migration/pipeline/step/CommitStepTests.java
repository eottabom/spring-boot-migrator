package com.eottabom.migration.pipeline.step;

import java.nio.file.Path;
import java.util.List;

import com.eottabom.migration.GitFixture;
import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.workspace.Git;
import com.eottabom.migration.workspace.StageFiles;
import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CommitStepTests {

	@TempDir
	Path temp;

	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource({ "01-boot-3.4, chore: Spring Boot 3.4 마이그레이션 (detail)", "02-java21, chore: Java 21 마이그레이션 (detail)",
			"03-gradle8.14, chore: Gradle 8.14 마이그레이션 (detail)" })
	void commitSubjectNamesWhatTheStageUpgrades(String tag, String expectedSubject) {
		Path repo = this.temp.resolve("repo");
		GitFixture.write(repo.resolve("build.gradle"), "plugins {}\n");
		GitFixture.init(repo);
		GitFixture.write(repo.resolve("build.gradle"), "plugins { id 'java' }\n");

		new CommitStep(new RunnerConsole(Logging.getLogger(CommitStepTests.class))).commit(new Git(repo), List.of(),
				new StageFiles(this.temp.resolve("stage")), StageTag.parse(tag), "(detail)");

		assertThat(GitFixture.git(repo, "log", "-1", "--format=%s").trim()).isEqualTo(expectedSubject);
	}

}
