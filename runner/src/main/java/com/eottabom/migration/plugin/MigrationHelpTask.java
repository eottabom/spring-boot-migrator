package com.eottabom.migration.plugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

/** ./gradlew migrationHelp : 태스크와 옵션 안내. */
public abstract class MigrationHelpTask extends DefaultTask {

	static final String USAGE = """
			Spring Boot 마이그레이션 (OpenRewrite)

			태스크
			  migrationScan      현재 Boot / Gradle / Java, resolve 된 의존성, detect 레시피가 찾은 위치 (소스 안 바뀜)
			  migrationPlan      실행할 stage, 호환성 판단 근거, stage 별 체크리스트 미리보기 (대상 Gradle 을 띄우지 않음)
			  migrationRun       stage 별 마이그레이션: rewriteRun → compile → build(테스트) → 결과 → (commit)
			  migrationVerify    현재 소스의 컴파일 + 전체 테스트 (소스 안 바뀜)
			  migrationHelp      이 안내

			공통 옵션
			  --project=<경로>              대상 프로젝트 (필수). 상대 경로는 명령을 실행한 위치 기준
			  --config=<파일>               설정 파일 (기본: 대상 프로젝트의 spring-boot-migrator.yml, 없으면 기본값)

			migrationPlan / migrationRun
			  --boot=<버전>                 목표 Boot: 3.0 ~ 3.5 | 4.0 | 4.1 (기본: 마지막 stage)
			  --java=<값>                   latest(기본: 목표 Boot 가 지원하는 가장 높은 LTS) | keep(지원하면 유지) | 17 | 21 | 25
			  --mode=<값>                   staged(기본: stage 마다 게이트) | all(한 번에 적용, 게이트 한 번) | preview(patch 만)

			migrationRun
			  --gate=<값>                   build(기본: 컴파일 + 전체 테스트 + 패키징) | compile | none
			  --commit                      게이트를 통과한 stage 마다 git commit (작업 트리가 깨끗해야 함)
			  --allow-dirty                 커밋되지 않은 변경이 있어도 시작

			migrationVerify
			  --gate=<값>                   build(기본) | compile

			설정 파일 (spring-boot-migrator.yml, schema/config.schema.json)
			  gate.testRetries, gate.baselineTests, recipes.custom, recipes.project,
			  build.jdk, build.jvmArgs, build.timeoutMinutes 등 자주 바꾸지 않는 값. CLI 옵션이 파일보다 우선해요

			예
			  ./gradlew migrationPlan --project=../my-api --boot=3.5
			  ./gradlew migrationRun  --project=../my-api --commit
			  ./gradlew migrationRun  --project=../my-api --boot=4.0 --java=keep --mode=preview

			결과는 대상 프로젝트의 .spring-boot-migrator/ (result.html, stage 별 결과와 patch, history.md).
			멈춘 뒤 고치고 같은 명령을 다시 실행하면 멈춘 stage 부터 이어서 진행해요.
			프로젝트 전용 레시피: 대상 프로젝트의 .rewrite/ 에 tags ["migration-stage:4.0", "migration-order:before|after"]
			자세한 내용: docs/usage.md
			""";

	@TaskAction
	public void print() {
		// -q 로 실행해도 보이도록 quiet 레벨로 찍는다
		getLogger().quiet(USAGE);
	}

}
