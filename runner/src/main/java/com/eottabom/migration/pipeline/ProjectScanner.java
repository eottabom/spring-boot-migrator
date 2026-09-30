package com.eottabom.migration.pipeline;

import java.nio.file.Path;

import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.gradle.VerifyInitScript;
import com.eottabom.migration.pipeline.MigrationRunner.RunnerPaths;
import com.eottabom.migration.result.DependencyChanges;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.RunFiles;
import org.jspecify.annotations.Nullable;

/** 소스를 바꾸지 않고 resolve 된 의존성 버전과 detect 레시피가 찾은 위치를 모은다. */
record ProjectScanner(RunnerPaths paths) {

	static final String DETECT_RECIPE = "com.eottabom.rewrite.detect.ManualMigrationItems";

	boolean resolvedVersions(ProjectGradle gradle, Path log, Path out) {
		return gradle.run(log, verifyInitScript().args("migrationResolvedVersions", "-PmigrationVersionsOut=" + out));
	}

	/** 빌드 파일에서 찾지 못한 Boot 버전을 대상 Gradle 이 resolve 한 spring-boot 버전으로 읽는다 */
	@Nullable String resolvedBootVersion(ProjectGradle gradle, RunFiles files) {
		if (!resolvedVersions(gradle, files.inspectVersionsLog(), files.inspectVersions())) {
			return null;
		}
		return DependencyChanges.readVersions(files.inspectVersions()).get("org.springframework.boot:spring-boot");
	}

	/** detect 레시피를 rewriteDryRun 으로 돌린다. 실패하면 빈 patch 를 남긴다 */
	boolean detect(Path projectDir, ProjectGradle gradle, RunFiles files) {
		boolean scanned = gradle.rewrite(files.detectLog(), "rewriteDryRun", DETECT_RECIPE, this.paths.rewriteInit(),
				this.paths.recipeLibs());
		if (scanned) {
			MigrationWorkspace.copyOrEmpty(rewritePatch(projectDir), files.detectPatch());
		}
		else {
			MigrationWorkspace.writeEmpty(files.detectPatch());
		}
		return scanned;
	}

	VerifyInitScript verifyInitScript() {
		return new VerifyInitScript(this.paths.verifyInit());
	}

	static Path rewritePatch(Path projectDir) {
		return projectDir.resolve("build/reports/rewrite/rewrite.patch");
	}

}
