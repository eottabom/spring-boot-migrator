package com.eottabom.migration.pipeline;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.pipeline.MigrationRunner.RunnerPaths;
import com.eottabom.migration.plan.MigrationPlanner;
import com.eottabom.migration.project.ProjectInspector;
import org.gradle.api.logging.Logger;

/** 러너가 실행 내내 함께 쓰는 협력 객체. */
record RunnerComponents(RunnerConsole console, ProjectInspector inspector, MigrationPlanner planner, Guides guides,
		ProjectScanner scanner) {

	static RunnerComponents assemble(RunnerPaths paths, Logger logger) {
		Guides guides = Guides.load(paths.guidesDir(), paths.schemaDir());
		return new RunnerComponents(new RunnerConsole(logger), new ProjectInspector(), new MigrationPlanner(guides),
				guides, new ProjectScanner(paths));
	}

}
