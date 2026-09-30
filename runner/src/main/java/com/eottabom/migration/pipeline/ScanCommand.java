package com.eottabom.migration.pipeline;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.misc.TextFiles;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.RunFiles;

/**
 * migrationScan. 현재 상태, resolve 된 의존성, detect 레시피가 찾은 위치를 보여준다. 소스는 바꾸지 않는다.
 */
record ScanCommand(ProjectScanner scanner, RunnerConsole console) {

	void scan(ProjectState project, ProjectGradle gradle) {
		RunFiles files = MigrationWorkspace.in(project.dir()).scan();
		this.console.project(project, gradle.javaHome());
		this.console.projectRecipes(project.dir(), ProjectRecipes.discover(project.dir()));

		this.console.heading("[scan] 의존성 버전");
		if (this.scanner.resolvedVersions(gradle, files.versionsLog(), files.versions())) {
			this.console.line("   의존성 {}개 → {}", TextFiles.countMatches(files.versions(), "."), files.versions());
		}
		else {
			this.console.error("의존성 버전 수집 실패 → " + files.versionsLog());
		}

		this.console.heading("[scan] 수동 검토 대상 (detect)");
		if (this.scanner.detect(project.dir(), gradle, files)) {
			this.console.line("   {} 곳 → {}", TextFiles.countMatches(files.detectPatch(), "~~>"), files.detectPatch());
		}
		else {
			this.console.error("detect 실패 → " + files.detectLog());
		}
	}

}
