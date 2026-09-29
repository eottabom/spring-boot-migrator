# spring-boot-migrator 재설계 초안

지금 구조는 기능을 덧붙이며 커졌다. 이 문서는 경계, 이름, 데이터 형식을 다시 정하는 초안이다.
동작(단계별 마이그레이션, 게이트, 재개, 결과 페이지)은 그대로 두고 구조만 바꾼다.

## 구현 상태

이 초안대로 구현했다. 구현하면서 바뀐 것은 아래와 같다. 최신 구조는 [Architecture](architecture.md) 와 [Usage](usage.md) 가 기준이다.

| 초안 | 구현 |
|---|---|
| 러너가 upstream, custom, catalog, 공통 보정을 조립 | 3.0 처럼 custom 이 upstream 보다 먼저 돌아야 하는 stage 가 있어 순서는 `stage.*` 레시피가 정하고, 러너는 stage 레시피를 이어 붙인다 |
| custom 은 역할 기준 `fix` | 출처 기준 `custom` (검색은 `detect`) |
| step 은 Prepare, Rewrite, Gate, Assess, Record, Commit, Finish | 컴파일 경고의 deprecated API 를 대체 레시피로 바로 바꾸는 Deprecation step 을 Gate(compile) 와 Gate(build) 사이에 넣었다 |
| 재개는 마지막으로 끝난 step 다음부터 | 멈춘 stage 의 게이트부터 다시 확인한다 (run-state.json 의 stopped) |
| 최소 Boot 2.7 | 2.5 |
| 기본 Java 는 Gradle 공식 지원 목록 때문에 낮출 수 있음 | 목표 Boot 가 지원하는 가장 높은 LTS. 필요한 Gradle 은 Java stage 앞의 Gradle stage(8.14, 9.1) 로 올린다 |
| `upstream.catalog` 규칙에서 2.0.x ~ 2.4.x 목표 제거 | 하지 않았다. 2.x 체인의 좌표 변경(`change`) 규칙은 2.5 이상에서도 필요해서 버전만 보고 뺄 수 없다 |

## 목표

1. 패키지마다 책임 하나. 의존 방향을 테스트로 고정한다
2. 단계 안의 동작을 step 으로 나누고 step 사이는 레코드로만 주고받는다
3. 옵션을 줄이고, 자주 바꾸지 않는 값은 설정 파일로 옮긴다
4. 손으로 쓰는 데이터(가이드, 설정, 결과, 재개 기록)는 JSON Schema 로 검증한다
5. upstream 레시피와 커스텀 레시피를 이름만 보고 구분할 수 있게 한다
6. yml 의 반복을 없앤다. 관례로 알 수 있는 값은 적지 않는다

하지 않는 것은 CI, 이 저장소의 Java 25 전환(별도 작업), 새 마이그레이션 기능이다.

## 용어

한 단어는 한 가지 뜻으로만 쓴다.

| 용어 | 뜻 | 쓰지 않는 말 |
|---|---|---|
| project | 마이그레이션할 대상 프로젝트 | target (목표 버전과 헷갈린다) |
| target | 목표 버전 (Boot, Java) | |
| stage | 버전을 한 칸 올리는 단위. `3.4`, `java21`, `gradle8.14` | phase, step |
| step | stage 안의 동작 하나. Rewrite, Gate, Assess, Record, Commit | stage |
| guide | Boot, Java, Gradle, 라이브러리의 한 버전으로 올릴 때 보는 정보 (지원 범위, 체크리스트, 실패 힌트) | playbook, known issues, compatibility |
| checklist | stage 를 올린 뒤 사람이 확인할 항목 | known issues |
| upstream | OpenRewrite 원본 레시피와 거기서 생성한 파생 레시피 | |
| custom | 이 저장소에서 직접 만든 보정 레시피 | fix, patch |
| detect | 코드를 바꾸지 않고 위치만 찾는 레시피 | find-manual |
| baseline | 마이그레이션 전 원본 빌드 결과 | |
| result | stage 와 실행 전체의 결과물 | report |

## 저장소 구조

```
spring-boot-migrator/
  guides/                        버전별 가이드 (지금 playbook/)
    boot/3.0.yml ... 4.1.yml
    java/17.yml 21.yml 25.yml
    gradle/8.14.yml
    libraries/hibernate-orm.yml ...
    common.yml                   모든 stage 에 쓰는 실패 힌트
  schema/                        JSON Schema
    guide.schema.json
    library-guide.schema.json
    config.schema.json
    result.schema.json
    run-state.schema.json
  init/                          대상 Gradle 에 붙이는 init script
  recipes/                       OpenRewrite 레시피 (upstream 파생, custom, detect)
  runner/                        Gradle 플러그인
  docs/
```

### 왜 guides 인가

지금 `playbook/` 에는 지원 범위(compatibility.yml)와 확인 항목(known-issues.yml)이 종류별로 나뉘어 있다.
새 Boot 버전을 추가하려면 두 파일과 레시피 yml 을 모두 고쳐야 한다. 내용의 출처도 결국 버전별 마이그레이션 가이드와 릴리스 노트다.

그래서 종류가 아니라 버전 기준으로 나누고, 파일 하나를 "그 버전으로 올릴 때 보는 가이드" 로 본다.
공식 가이드를 러너가 읽을 수 있는 형태로 옮긴 것이라는 성격이 이름에 드러난다.
`guides/boot/4.2.yml` 파일 하나를 추가하면 새 stage 가 생긴다. stage 순서는 파일 이름의 버전 순서로 정한다. 따로 목록을 두지 않는다.

| 후보 | 판단 |
|---|---|
| `guides/` (선택) | 공식 가이드를 옮긴 것이라는 성격이 드러난다. 파일 안의 공식 문서 URL 은 `source` 로 불러 이름이 겹치지 않게 한다 |
| `versions/` | 구조는 드러나지만 흔한 단어이고 Gradle `JavaVersion` 과 클래스 이름이 겹친다 |
| `releases/` | 릴리스 하나가 파일 하나라는 뜻은 맞지만 배포(release) 로도 읽힌다 |
| `stages/` | 라이브러리 항목과 공통 실패 힌트는 stage 가 아니다 |
| `playbook/` (지금) | 다른 도구(Ansible) 용어이고 무엇이 들었는지 드러나지 않는다 |

## guides 데이터 형식

### Boot 가이드 (`guides/boot/3.4.yml`)

```yaml
# yaml-language-server: $schema=../../schema/guide.schema.json
source: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.4-Release-Notes

requirements:
  java: { min: 17, max: 24 }
  gradle: { "7": "7.6.4", "8": "8.4" }
  framework: "6.2"
  springCloud: { train: 2024.0.x, since: 2024.0.0 }
  springCloudAws: 3.3.x

checklist:
  - id: conditional-component-scan
    title: 빈 등록 단계 조건과 ComponentScan 조합의 기동 실패
    detail: Framework 6.2 는 ComponentScan 과 REGISTER_BEAN 단계 조건의 조합을 거부한다. ...
    detect: com.eottabom.rewrite.detect.spring.FindLateConditionalComponentScan
    source: https://github.com/spring-projects/spring-framework/issues/23206
  - id: mockbean-replaced
    title: MockBean 과 SpyBean 이 MockitoBean 으로 바뀜
    fix: auto
    recipe: com.eottabom.rewrite.custom.testing.ReplaceMockBeanAndSpyBean
  - id: spy-caching-proxy
    title: 캐시 프록시를 거치는 spy stubbing 이 기본값을 캐시할 수 있음
    when: { dependencies: [org.springframework:spring-test] }
    detect: com.eottabom.rewrite.detect.testing.FindSpyStubbingThroughCachingProxy
    source: https://github.com/spring-projects/spring-framework/issues/37121

failureHints:
  - pattern: "NoSuchBeanDefinitionException.*'taskExecutor'"
    text: Boot 3.5 에서 taskExecutor 별칭이 없어졌어요. applicationTaskExecutor 로 바꿔 주세요
```

적지 않아도 되는 값과 기본값은 이렇다.

| 값 | 기본값 또는 관례 |
|---|---|
| 버전 | 파일 이름 (`3.4.yml`) |
| checklist 항목의 `fix` | `manual` |
| checklist 항목의 `source` | 파일의 `source` |
| checklist 항목의 전역 id | `boot-3.4/conditional-component-scan` (파일 경로 + id) |
| stage 레시피 | `upstream.Boot_3_4`, `custom.Boot_3_4`, `upstream.catalog.Boot_3_4` (없으면 건너뛴다) |

`fix` 는 누가 고치는지를 뜻한다.

| 값 | 뜻 | 필요한 필드 |
|---|---|---|
| `auto` | 레시피가 고쳤다. 결과만 확인한다 | `recipe` |
| `assisted` | 레시피가 바꿨지만 사람이 확인해야 한다 | `recipe` |
| `manual` | 사람이 처리한다 (기본값) | |

`failureHints` 를 가이드 파일에 두면 그 stage 의 실패에만 힌트가 붙는다. 지금처럼 모든 stage 에 같은 힌트를 거는 것보다 추측이 줄어든다.
Docker 연결 실패처럼 stage 와 무관한 힌트만 `guides/common.yml` 에 둔다.

### Java, Gradle 가이드

```yaml
# guides/java/25.yml
gradle: "9.1"             # 이 JDK 에서 Gradle 을 띄우려면 필요한 최소 버전
checklist: [...]

# guides/gradle/9.1.yml
checklist: [...]
```

레시피 이름은 Boot 와 같은 관례(`upstream.Java_25`, `custom.Java_25`, `custom.Gradle_9_1`)를 따른다.

Java stage 는 LTS(17, 21, 25) 만 둔다. Java 를 올릴 때 Gradle 도 같이 올린다.
플래너는 현재 Gradle 이 Java 가이드의 `gradle` 보다 낮으면 Java stage 바로 앞에 그 Gradle stage 를 넣는다.

```
Boot 3.5, Java 21, Gradle 8.14 → 목표 Java 25
  ... → gradle9.1 → java25
```

| Java | 함께 올리는 Gradle | Gradle stage 레시피 |
|---|---|---|
| 17 | 7.3 이상 | (현재 Gradle 이 대부분 충족. 부족하면 gradle8.14) |
| 21 | 8.5 이상 | `custom.Gradle_8_14` |
| 25 | 9.1 이상 | `custom.Gradle_9_1` (새로 만든다) |

Gradle stage 레시피는 wrapper 와 그 Gradle 에서 동작하지 않는 서드파티 플러그인(freefair, asciidoctor, sonarqube, gradle-git-properties 등)을 함께 올린다.
지금은 Gradle 9.1 로의 전환이 upstream UpgradeToJava25 안에 숨어 있고 플러그인 정렬도 Java 25 레시피에 섞여 있다.
이를 `custom.Gradle_9_1` 로 옮겨 Java stage 는 Java 만, Gradle stage 는 Gradle 만 바꾼다.
Gradle 이 목표 Boot 의 공식 지원 목록에 없으면 계획에 안내만 남기고 Java 를 낮추지 않는다.

### 라이브러리 가이드 (`guides/libraries/hibernate-orm.yml`)

stage 와 상관없이 resolve 된 버전이 바뀔 때 거는 항목이다. 지금 known-issues.yml 의 `dependency` 항목 15개가 여기로 온다.

```yaml
library: org.hibernate.orm:hibernate-core
checklist:
  - id: json-dirty-checking
    crosses: "6.6"          # 이전 < 6.6 <= 이후 일 때
    title: ...
  - id: entity-graph-join-reuse
    affected: ["6.5.0", "6.5.3"]   # [from, until) 에 이후 버전이 들어갈 때
    title: ...
```

## 레시피 구성

### 이름공간

| 이름공간 | 내용 | 파일 |
|---|---|---|
| `org.openrewrite.*` | upstream 원본 | jar |
| `com.eottabom.rewrite.upstream.*` | upstream 에서 직전 stage 체인을 뺀 사본, catalog 규칙, upstream 에 아직 없는 stage 의 대체 (생성) | `META-INF/rewrite/upstream/*.yml` |
| `com.eottabom.rewrite.custom.<도메인>` | 직접 만든 보정 | `META-INF/rewrite/custom/<도메인>.yml`, Java 클래스 |
| `com.eottabom.rewrite.detect.<도메인>` | 직접 만든 검색 | `META-INF/rewrite/detect/<도메인>.yml`, Java 클래스 |

도메인이 없는 custom 보정(`UnshadeRelocatedImports` 등)은 `custom.misc` 에 둔다.

### stage 레시피 조립

지금은 `SpringBootStep_X_Y` 한 레시피 안에 upstream, custom, catalog, 공통 보정이 섞여 있고
`MigrateToSpringBoot_X_Y` 체인 레시피 8개와 `UpstreamOnlyStep_X_Y` 8개가 따로 있다.

러너가 stage 마다 아래 순서로 조립해 `.rewrite/rewrite.assembled.yml` 에 쓴다.

```
프로젝트 레시피 (migration-order:before)
upstream.Boot_3_4
custom.Boot_3_4              --upstream-only 면 뺀다 (설정의 recipes.custom: false)
upstream.catalog.Boot_3_4
custom.StageEpilogue         모든 stage 마지막의 공통 보정 (지금 CommonMigrationFixes, AddPropertiesMigrator). upstream-only 면 뺀다
프로젝트 레시피 (migration-order:after)
```

`--mode=all` 은 목표까지의 stage 레시피를 차례로 이어 붙여 한 번에 돌린다.
그래서 `MigrateToSpringBoot_X_Y`, `UpstreamOnlyStep_X_Y`, 각 stage 의 Singleton precondition 이 모두 없어진다.
러너 없이 레시피 jar 만 가져가 쓰는 용도는 지원하지 않는다.

### yml 정리

| 대상 | 정리 |
|---|---|
| 박스 주석, 구분선, 긴 머리 주석 | 지운다. 설명은 architecture.md 로 |
| detect 레시피의 description | 한 줄로. 자세한 설명은 checklist 항목 한 곳에만 둔다 (지금 11개가 글자까지 중복) |
| 필요 없는 따옴표 | 뺀다 |
| 생성 파일의 긴 description | 생성하지 않는다 |
| 3.0 catalog 규칙의 2.0.x ~ 2.4.x 목표 | 지원하는 최소 Boot 가 2.5 라서 뺀다 (UpgradeDependencyVersion 은 버전을 내리지 않아 동작은 같다) |

## runner 구조

### 패키지

```
com.eottabom.migration
  plugin/      Gradle 어댑터. MigrationPlugin, 태스크. 옵션을 MigrationConfig 로 바꿔 넘긴다
  config/      MigrationConfig, 설정 파일 읽기, 스키마 검증, CLI 값 병합
  project/     대상 프로젝트 조사. ProjectInspector, ProjectState, VersionCatalog, JdkLocator
  guide/       guides/ 읽기. BootGuide, JavaGuide, GradleGuide, LibraryGuide, ChecklistItem, FailureHint
  plan/        MigrationPlanner, MigrationPlan, Stage. 파일과 프로세스를 다루지 않는다
  recipe/      AssembledRecipe(stage 레시피 조립), ProjectRecipes(.rewrite/*.yml)
  pipeline/    MigrationPipeline, RunState, RunLock
    step/      PrepareStep, RewriteStep, GateStep, AssessStep, RecordStep, CommitStep, FinishStep
  gradle/      대상 Gradle 과의 접점. ProjectGradle, TestResults, FailedTasks, VerifyScript
  workspace/   .spring-boot-migrator/ 파일, Git, patch
  result/      StageResult, ResultMarkdown, ResultHtml
  misc/        AtomicFiles, Processes, TextFiles
```

### 의존 방향

```
plugin    → config, pipeline
pipeline  → step
step      → plan, recipe, gradle, workspace, result, guide, project
plan      → guide, project
result    → guide
recipe    → plan
모두      → misc
```

`plan`, `result`, `guide` 는 `gradle`, `workspace`, `pipeline` 을 모른다.
ArchUnit 테스트(테스트 의존성만)로 이 방향을 고정한다.

### 실행 흐름

```
migrationRun
  Inspect   → ProjectState
  Plan      → MigrationPlan (stages)
  Prepare   잠금, 작업 트리 확인, baseline build, run-state 로 재개 판단
  stage 마다
    Rewrite → RewriteOutcome   rewriteRun, 필요하면 JDK 다시 고르기
    Gate    → GateOutcome      compile, build, 불안정 테스트 재시도
    Assess  → Assessment       새 테스트 실패, checklist 매칭, 설정 키 변경, 제거 예정 API
    Record  → StageResult      result.md, result.json, patch
    Commit                     --commit 이고 게이트를 통과했을 때
  Finish    history.md, result.html, 실행 기록 정리
```

```java
for (Stage stage : plan.stages()) {
	RewriteOutcome rewrite = this.rewrite.run(stage);
	GateOutcome gate = this.gate.run(stage, rewrite);
	Assessment assessment = this.assess.run(stage, gate);
	StageResult result = this.record.run(stage, rewrite, gate, assessment);
	this.runState.completed(stage, result);
	if (!gate.passed()) {
		return Stop.at(stage, gate);
	}
	this.commit.run(stage, result);
}
```

- step 은 서로 부르지 않는다. 순서는 파이프라인만 안다.
- step 이 끝날 때마다 `run-state.json` 에 기록한다. 재개는 마지막으로 끝난 step 다음부터다. 지금 StageResumer 의 분기 대부분이 사라진다.
- `--mode=preview` 는 임시 worktree 에서 Rewrite 와 Record 만 도는 파이프라인 구성이다. StagePreview 를 따로 두지 않는다.
- `--mode=all` 은 모든 stage 레시피를 이어 붙인 stage 하나짜리 파이프라인이다. 게이트도 마지막에 한 번만 돈다.

## 옵션과 설정 파일

### 태스크

| 태스크 | 하는 일 |
|---|---|
| `migrationScan` | 현재 버전, resolve 된 의존성, detect 결과 (지금 migrationAnalyze) |
| `migrationPlan` | 실행할 stage 와 레시피 |
| `migrationRun` | 마이그레이션 |
| `migrationVerify` | 현재 소스의 compile, build |
| `migrationHelp` | 옵션 안내 |

### CLI 옵션 (15개에서 8개로)

| 옵션 | 기본 | 비고 |
|---|---|---|
| `--project=<경로>` | 필수 | 지금 `--project-path` |
| `--boot=<버전>` | 최신 stage | 지금 `--spring-boot` |
| `--java=<값>` | `latest` | `latest`(목표 Boot 가 지원하는 가장 높은 Java 중 Java stage 가 있는 버전), `keep`, `none`, `17`, `21`, `25` |
| `--mode=<값>` | `staged` | `staged`(stage 마다 게이트), `all`(목표까지 한 번에 적용하고 게이트 한 번), `preview`(소스를 바꾸지 않고 patch 만). 지금 `--one-shot`, `--preview` |
| `--gate=<값>` | `build` | `compile`, `build`, `none` |
| `--commit` | 끔 | |
| `--allow-dirty` | 끔 | |
| `--config=<파일>` | 대상 프로젝트의 `spring-boot-migrator.yml` | |

옛 값 이름(`--java=auto`, `--gate=test`)은 지운다.

### 설정 파일 (`spring-boot-migrator.yml`, 선택)

```yaml
# yaml-language-server: $schema=<저장소>/schema/config.schema.json
target:
  boot: "4.1"
  java: latest
mode: staged
gate:
  level: build
  testRetries: 1          # 지금 --test-retries
  baselineTests: true     # 지금 --skip-baseline-tests 의 반대
recipes:
  custom: true            # false 면 upstream 만 (지금 --upstream-only)
  project: true           # false 면 .rewrite/ 레시피를 붙이지 않는다 (지금 --skip-project-recipes)
build:
  jdk: auto               # current 면 JAVA_HOME 그대로 (지금 --keep-java-home)
  jvmArgs:                # 비우면 장비 메모리 기준 (지금 --gradle-jvmargs)
  timeoutMinutes: 180     # 0 이면 제한 없음 (지금 --build-timeout)
commit: false
allowDirty: false
```

값을 고르는 순서는 CLI, 설정 파일, 스키마의 `default` 순이다. 코드의 `MigrationConfig` 는 이 구조를 그대로 레코드로 옮긴다.

## 결과물 (`.spring-boot-migrator/`)

```
.spring-boot-migrator/
  result.html            전체 결과 한 페이지
  history.md             실행마다 한 줄씩 (지금 SUMMARY.md)
  run-state.json         재개 기록 (지금 .resume, Properties)
  .lock
  03-boot-3.4/
    result.md
    result.json          result.html 이 읽는 데이터. 테스트 결과 파일, 불안정 테스트, 읽지 못한 XML 도 여기에
    cumulative.patch     시작 시점 대비 누적
    stage.patch          이 stage 만
    preview.patch        --mode=preview 일 때
    rewrite.log  compile.log  build.log
```

지금 따로 있는 `flaky-tests.txt`, `test-results.txt`, `unreadable-results.txt` 는 `result.json` 에 합친다.

### result.html 섹션

| 지금 | 바꾼 이름 |
|---|---|
| 자동 보정 내역 | 레시피 변경 (upstream, custom 을 나눠서) |
| 알려진 이슈 | 체크리스트 |
| 수동 검토 대상 검색 결과 | 검색 결과 (detect) |
| 컴파일, 테스트, 설정 키 변경, 제거 예정 API | 그대로 |

## JSON Schema

| 스키마 | 검증 대상 | 효과 |
|---|---|---|
| `guide.schema.json` | `guides/boot`, `java`, `gradle`, `common.yml` | 지금 KnownIssues.validate 와 Yaml 헬퍼의 수작업 검증을 대신한다. `fix: auto` 이면 `recipe` 필수 같은 규칙은 `if/then` 으로 |
| `library-guide.schema.json` | `guides/libraries/*.yml` | `crosses` 와 `affected` 중 하나만 (`oneOf`) |
| `config.schema.json` | `spring-boot-migrator.yml` | 기본값의 단일 출처. IDE 자동완성 |
| `result.schema.json` | `result.json` | result.html 템플릿과의 계약 |
| `run-state.schema.json` | `run-state.json` | 형식 버전을 둬서 옛 기록을 알아본다 |

라이브러리는 snakeyaml 과 직접 만든 Json 작성기를 빼고 Jackson(databind, dataformat-yaml)과 networknt json-schema-validator 를 쓴다.
플러그인은 이 저장소의 Gradle 안에서만 돌아 대상 프로젝트 classpath 와 충돌하지 않는다.
`guides/` 전체가 스키마를 통과하는지는 테스트 하나로 확인한다.

## 이름 바꾸기 표

| 지금 | 바꾼 이름 |
|---|---|
| `playbook/` | `guides/` |
| `compatibility.yml` | `guides/boot/*.yml` 의 `requirements`, `guides/java/*.yml` |
| `known-issues.yml` issues | 각 가이드 파일의 `checklist`, `guides/libraries/*.yml` |
| `known-issues.yml` failureHints | 각 가이드 파일의 `failureHints`, `guides/common.yml` |
| mode `AUTO_FIX`, `REVIEW_REQUIRED`, `REPORT_ONLY` | `fix: auto`, `assisted`, `manual` |
| `.rewrite-migration/` | `.spring-boot-migrator/` |
| `report.html` | `result.html` |
| `SUMMARY.md` | `history.md` |
| `migration-phase:before` | `migration-order:before` |
| `rewrite.generated.yml` | `rewrite.assembled.yml` |
| `SpringBootStep_X_Y` | `upstream.Boot_X_Y` + `custom.Boot_X_Y` |
| `CommonMigrationFixes` | `custom.StageEpilogue` |
| `FindManualMigrationItems` | `detect.ManualMigrationItems` |
| `MigrationRunner`, `MigrationSession` | `MigrationPipeline` |
| `StageGate`, `GateResult` | `GateStep`, `GateOutcome` |
| `StageReporter`, `StageCommitter` | `RecordStep`, `CommitStep` |
| `StageResumer`, `.resume` | `RunState`, `run-state.json` |
| `TargetGradle` | `ProjectGradle` |
| `ProjectModel` | `ProjectState` |
| `MigrationRequest` | `MigrationConfig` |
| `GateMode`, `JavaOption` | `Gate`, `JavaTarget` |
| `KnownIssues`, `Compatibility` | `Guides` (BootGuide, JavaGuide, GradleGuide, LibraryGuide) |
| `RecipeFixes` | `RecipeChanges` |
| `report` 패키지 | `result` |
| `support` 패키지 | `misc` |

## 진행 순서

각 단계는 `./gradlew check` 를 통과한 상태로 커밋하고 푸시한다. 동작은 기존 테스트로 지킨다.

| 순서 | 작업 | 끝났다고 보는 기준 |
|---|---|---|
| 1 | runner 패키지 이동, 클래스 이름 변경, ArchUnit 규칙 | 동작 변경 없음. 기존 테스트 통과 |
| 2 | Jackson 과 스키마 도입, `playbook/` 을 `guides/` 로 변환 | 변환 전후 stage 마다 고른 checklist 가 같다 |
| 3 | `MigrationConfig`, 설정 파일, 옵션 축소, 태스크 이름 변경 | 옛 옵션 조합마다 같은 config 가 나온다 |
| 4 | 레시피 이름공간(upstream, custom, detect), 러너의 stage 레시피 조립, 체인 레시피 삭제 | 예제 프로젝트에서 stage 마다 patch 가 같다 |
| 5 | 파이프라인과 step 분리, `run-state.json` 재개 | 재개 시나리오 테스트 통과 |
| 6 | `.spring-boot-migrator/` 결과물 구조, `result.html`, `result.json` 스키마 | 결과 페이지가 모든 섹션을 보여 준다 |
| 7 | yml 정리, 생성기 정리, README 와 docs 갱신 | 문서의 명령과 파일 이름이 실제와 같다 |

## 정한 것

| 항목 | 결정 |
|---|---|
| 기준 데이터 디렉토리 | `guides/` |
| 확인 항목 이름 | `checklist` |
| 태스크와 옵션 이름 | `migrationScan`, `--project`, `--boot` |
| 지원하는 최소 Boot | 2.5. 그보다 낮으면 plan 에서 거부하고 2.5 로 먼저 올리라고 안내한다 |
| 설정 파일 | 대상 프로젝트 루트의 `spring-boot-migrator.yml`, 다른 위치는 `--config` |
| 체인 레시피 | 지운다. 러너 없이 레시피 jar 만 쓰는 용도는 지원하지 않는다 |
| 한 번에 적용하는 방식 | `--one-shot` 대신 `--mode=all` |
| 기본 Java | 목표 Boot 가 지원하는 가장 높은 LTS (17, 21, 25). Gradle 공식 지원 목록 때문에 낮추지 않는다 |
| Gradle | Java 를 올릴 때 필요한 Gradle stage(`gradle8.14`, `gradle9.1`) 를 Java stage 앞에 함께 넣는다. `custom.Gradle_9_1` 을 새로 만든다 |
