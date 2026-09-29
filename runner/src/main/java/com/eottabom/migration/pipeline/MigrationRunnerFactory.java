package com.eottabom.migration.pipeline;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.pipeline.MigrationRunner.RunnerPaths;
import com.eottabom.migration.plan.MigrationPlanner;
import com.eottabom.migration.project.ProjectInspector;
import org.gradle.api.logging.Logger;

/** 러너 협력 객체의 조립. */
final class MigrationRunnerFactory {

	private MigrationRunnerFactory() {
	}

	static Components assemble(RunnerPaths paths, Logger logger) {
		Guides guides = Guides.load(paths.guidesDir(), paths.schemaDir());
		return new Components(new RunnerConsole(logger), new ProjectInspector(), new MigrationPlanner(guides), guides,
				new ProjectScanner(paths));
	}

	record Components(RunnerConsole console, ProjectInspector inspector, MigrationPlanner planner, Guides guides,
			ProjectScanner scanner) {
	}

}
