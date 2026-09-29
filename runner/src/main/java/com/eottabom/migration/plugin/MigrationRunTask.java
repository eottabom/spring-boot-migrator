package com.eottabom.migration.plugin;

import com.eottabom.migration.config.MigrationConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.options.Option;

/** 목표 Boot 까지 stage 별로 마이그레이션한다. 결과와 patch 는 대상 프로젝트의 .spring-boot-migrator/ 에 남는다. */
public abstract class MigrationRunTask extends MigrationPlanTask {

	@Internal
	@Option(option = "gate", description = "build(기본: 컴파일 + 전체 테스트 + 패키징과 검사 태스크) | compile | none")
	public abstract Property<String> getGate();

	@Internal
	@Option(option = "commit", description = "게이트를 통과한 stage 마다 git commit (작업 트리가 깨끗해야 해요)")
	public abstract Property<Boolean> getCommit();

	@Internal
	@Option(option = "allow-dirty", description = "커밋되지 않은 변경이 있어도 시작해요")
	public abstract Property<Boolean> getAllowDirty();

	@Override
	protected void perform(MigrationConfig config) {
		runner(config).run(config);
	}

	@Override
	protected void addOptions(ObjectNode cli) {
		super.addOptions(cli);
		if (getGate().isPresent()) {
			cli.putObject("gate").put("level", getGate().get());
		}
		if (getCommit().isPresent()) {
			cli.put("commit", getCommit().get());
		}
		if (getAllowDirty().isPresent()) {
			cli.put("allowDirty", getAllowDirty().get());
		}
	}

}
