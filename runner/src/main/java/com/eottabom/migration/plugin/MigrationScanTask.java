package com.eottabom.migration.plugin;

import org.gradle.api.tasks.TaskAction;

/** 현재 Boot, Gradle, Java 버전과 resolve 된 의존성, detect 레시피가 찾은 위치. 소스는 바꾸지 않는다. */
public abstract class MigrationScanTask extends MigrationTask {

	@TaskAction
	public void scan() {
		run((config) -> runner(config).scan(config));
	}

}
