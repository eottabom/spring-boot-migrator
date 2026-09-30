package com.eottabom.migration.pipeline;

import java.nio.file.Path;

import com.eottabom.migration.config.MigrationConfig;
import com.eottabom.migration.config.MigrationConfig.BuildSettings;
import com.eottabom.migration.gradle.GradleWrapperProcess;
import com.eottabom.migration.gradle.InitScripts;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.plan.MigrationPlan;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.workspace.MigrationWorkspace;
import org.gradle.api.logging.Logger;

/**
 * 태스크 진입점. migrationScan, migrationPlan, migrationVerify 는 여기서 끝나고 migrationRun 은
 * {@link MigrationPipeline} 이 돈다.
 */
public final class MigrationRunner {

	private final RunnerComponents components;

	/**
	 * @param build 대상 Gradle 을 띄우는 설정 (JVM 옵션, 제한 시간)
	 */
	public MigrationRunner(RunnerPaths paths, BuildSettings build, Logger logger) {
		this(paths, logger, (dir, javaHome) -> new GradleWrapperProcess(dir, javaHome, build.jvmArgs(), build.timeout(),
				paths.scripts(), logger));
	}

	/** 대상 빌드 실행을 바꿔 끼운다 (러너 통합 테스트) */
	MigrationRunner(RunnerPaths paths, Logger logger, ProjectGradle.Factory gradleFactory) {
		this.components = RunnerComponents.assemble(paths, logger, gradleFactory);
	}

	/** 현재 상태, resolve 된 의존성, detect 레시피가 찾은 위치. 소스는 바꾸지 않는다. */
	public void scan(MigrationConfig config) {
		ProjectState inspected = this.components.inspector().inspect(config.projectDir());
		ProjectGradle gradle = this.components.gradle(inspected, config);
		ProjectState project = this.components.withResolvedBootVersion(inspected, gradle,
				MigrationWorkspace.in(config.projectDir()).scan());
		new ScanCommand(this.components.scanner(), this.components.console()).scan(project, gradle);
	}

	/** 실행할 stage 만 보여준다. 대상 프로젝트의 Gradle 을 띄우지 않는다. */
	public MigrationPlan plan(MigrationConfig config) {
		ProjectState project = this.components.inspector().inspect(config.projectDir());
		MigrationPlan plan = this.components.planner().plan(project, config);
		new PlanPrinter(this.components.console(), this.components.guides()).print(project, plan,
				RunnerComponents.projectRecipes(config));
		return plan;
	}

	/** 현재 소스의 컴파일(+제거 예정 API 경고)과 build(전체 테스트 + 패키징). 소스는 바꾸지 않는다. */
	public void verify(MigrationConfig config) {
		ProjectState project = this.components.inspector().inspect(config.projectDir());
		new VerifyCommand(this.components.console()).verify(project, this.components.gradle(project, config),
				config.gate().level());
	}

	public void run(MigrationConfig config) {
		MigrationWorkspace ws = MigrationWorkspace.in(config.projectDir());
		RunLock lock = RunLock.acquire(ws);
		try {
			new MigrationPipeline(this.components, config, ws).run();
		}
		finally {
			lock.close();
		}
	}

	/**
	 * @param scripts 대상 Gradle 에 붙이는 init script 와 레시피 jar
	 * @param guidesDir guides/ (버전별 가이드)
	 * @param schemaDir schema/ (가이드, 설정 파일, 재개 기록의 JSON Schema)
	 */
	public record RunnerPaths(InitScripts scripts, Path guidesDir, Path schemaDir) {
	}

}
