package com.eottabom.migration.plugin;

import com.eottabom.migration.config.MigrationConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

/** 실행할 stage 와 레시피를 보여준다. 대상 프로젝트의 Gradle 을 띄우지 않는다. */
public abstract class MigrationPlanTask extends MigrationTask {

	@Internal
	@Option(option = "boot", description = "목표 Boot: 3.0 ~ 3.5 | 4.0 | 4.1 (기본: 마지막 stage)")
	public abstract Property<String> getBoot();

	@Internal
	@Option(option = "java",
			description = "목표 Java: latest(기본: 목표 Boot 가 지원하는 가장 높은 LTS) | keep(지원하면 유지) | 17 | 21 | 25 | none")
	public abstract Property<String> getJava();

	@Internal
	@Option(option = "mode", description = "staged(기본: stage 마다 게이트) | all(한 번에 적용하고 게이트 한 번) | preview(patch 만)")
	public abstract Property<String> getMode();

	@TaskAction
	public void execute() {
		MigrationConfig config = config();
		perform(config);
	}

	protected void perform(MigrationConfig config) {
		runner(config).plan(config);
	}

	@Override
	protected void addOptions(ObjectNode cli) {
		if (getBoot().isPresent()) {
			cli.putObject("target").put("boot", getBoot().get());
		}
		if (getJava().isPresent()) {
			ObjectNode target = cli.has("target") ? (ObjectNode) cli.get("target") : cli.putObject("target");
			target.put("java", getJava().get());
		}
		if (getMode().isPresent()) {
			cli.put("mode", getMode().get());
		}
	}

}
