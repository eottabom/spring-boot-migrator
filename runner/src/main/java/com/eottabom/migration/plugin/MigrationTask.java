package com.eottabom.migration.plugin;

import java.nio.file.Files;
import java.nio.file.Path;

import com.eottabom.migration.config.ConfigLoader;
import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.pipeline.MigrationRunner;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.options.Option;

/**
 * 대상 프로젝트를 다루는 태스크의 공통 옵션. 대상 프로젝트는 이 빌드의 입력/출력이 아니므로 up-to-date 검사를 하지 않는다. 자주 바꾸지 않는 값은
 * 대상 프로젝트의 spring-boot-migrator.yml 에 둔다 (schema/config.schema.json).
 */
public abstract class MigrationTask extends DefaultTask {

	@Internal
	@Option(option = "project", description = "대상 프로젝트 경로 (필수)")
	public abstract Property<String> getProjectPath();

	@Internal
	@Option(option = "config", description = "설정 파일 (기본: 대상 프로젝트의 spring-boot-migrator.yml)")
	public abstract Property<String> getConfig();

	/** 명령을 실행한 위치. 상대 경로 --project, --config 의 기준 */
	@Internal
	public abstract DirectoryProperty getInvocationDir();

	@Internal
	public abstract RegularFileProperty getRewriteInitScript();

	@Internal
	public abstract RegularFileProperty getVerifyInitScript();

	@Internal
	public abstract DirectoryProperty getRecipeLibs();

	@Internal
	public abstract DirectoryProperty getGuidesDir();

	@Internal
	public abstract DirectoryProperty getSchemaDir();

	/** 설정 파일과 CLI 옵션을 합친 설정 */
	protected MigrationConfig config() {
		ObjectNode cli = ConfigLoader.cli();
		addOptions(cli);
		Path configFile = getConfig().isPresent() ? resolve(getConfig().get()) : null;
		try {
			return new ConfigLoader(getSchemaDir().get().getAsFile().toPath()).load(projectDir(), configFile, cli);
		}
		catch (IllegalArgumentException ex) {
			throw new GradleException(String.valueOf(ex.getMessage()), ex);
		}
	}

	/** 태스크의 CLI 옵션을 설정 파일과 같은 구조로 넣는다 */
	protected void addOptions(ObjectNode cli) {
	}

	protected MigrationRunner runner(MigrationConfig config) {
		return new MigrationRunner(
				new MigrationRunner.RunnerPaths(getRewriteInitScript().get().getAsFile().toPath(),
						getVerifyInitScript().get().getAsFile().toPath(), getRecipeLibs().get().getAsFile().toPath(),
						getGuidesDir().get().getAsFile().toPath(), getSchemaDir().get().getAsFile().toPath()),
				config.build(), getLogger());
	}

	private Path projectDir() {
		if (!getProjectPath().isPresent()) {
			throw new GradleException("--project=<대상 프로젝트 경로> 가 필요해요. 옵션 안내: ./gradlew migrationHelp");
		}
		Path dir = resolve(getProjectPath().get());
		if (!Files.isRegularFile(dir.resolve("gradlew"))) {
			throw new GradleException("Gradle wrapper(gradlew) 가 있는 프로젝트가 아니에요: " + dir);
		}
		return dir;
	}

	/** 상대 경로는 이 저장소가 아니라 명령을 실행한 위치 기준 (./gradlew -p 로 실행해도 같다) */
	private Path resolve(String path) {
		return getInvocationDir().get().getAsFile().toPath().resolve(path).normalize().toAbsolutePath();
	}

}
