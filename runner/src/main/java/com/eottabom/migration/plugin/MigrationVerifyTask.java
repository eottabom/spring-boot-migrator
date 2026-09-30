package com.eottabom.migration.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

/** 현재 소스의 컴파일(+제거 예정 API 경고)과 build(전체 테스트 + 패키징). 소스는 바꾸지 않는다. */
public abstract class MigrationVerifyTask extends MigrationTask {

	@Internal
	@Option(option = "gate", description = "build(기본: 컴파일 + 전체 테스트 + 패키징) | compile")
	public abstract Property<String> getGate();

	@TaskAction
	public void verify() {
		run((config) -> runner(config).verify(config));
	}

	@Override
	protected void addOptions(ObjectNode cli) {
		if (getGate().isPresent()) {
			cli.putObject("gate").put("level", getGate().get());
		}
	}

}
