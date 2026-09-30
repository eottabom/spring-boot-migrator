# Usage

빠른 시작은 [README](../README.md), 내부 구조와 레시피 추가는 [Architecture](architecture.md) 를 본다.

## How It Works

```
./gradlew migrationRun --project=<경로>
 │
 ├─ 1) 레시피 빌드 ──── recipes/ 를 빌드해서 레시피 jar 와 의존 jar 를 recipes/build/recipe-libs/ 에 모은다 (recipeLibs 태스크 의존)
 │
 ├─ 2) 계획 ──────────── 현재 Boot / Gradle / Java 버전 감지 → guides/ 로 stage 결정
 │                       (빌드 파일에 Boot 버전이 없으면 대상 Gradle 이 resolve 한 spring-boot 버전을 쓴다. Boot 2.5 미만은 거부)
 │                       (Gradle 이 목표 Boot 지원 범위보다 낮으면 그 Boot stage 앞에 Gradle stage, 마지막에 목표 Boot 가 지원하는
 │                        가장 높은 LTS 까지 Java stage. 그 JDK 가 요구하는 Gradle 이 부족하면 Java stage 앞에 Gradle stage)
 │
 ├─ 3) 시작 ──────────── resolve 된 의존성 버전, 원본 빌드(원래 실패하던 태스크와 테스트), detect 레시피가 찾은 위치
 │
 ├─ 4) stage 마다 ────── 예) 3.3 -> 3.4 -> 3.5 -> gradle8.14 -> 4.0 -> 4.1 -> gradle9.1 -> java25
 │    ├─ Rewrite        stage 레시피와 프로젝트 레시피를 .rewrite/rewrite.assembled.yml 로 조립해 rewriteRun
 │    │                 (대상 프로젝트의 build.gradle 은 수정하지 않는다. 레시피가 Java 버전을 올렸으면 게이트 전에 JDK 를 다시 고른다)
 │    ├─ Gate compile   init/verify.init.gradle: javac -Xlint:deprecation,removal 로 deprecated 와 제거 예정 API 수집
 │    ├─ Deprecation    경고에 guides/ 의 대체 레시피가 있으면 같은 stage 안에서 바로 바꾸고 다시 compile
 │    ├─ Gate build     전체 테스트(fail-fast 끔) + 패키징, asciidoctor, checkstyle (--continue). 새로 실패한 테스트는 다시 돌려 본다
 │    ├─ Assess         테스트 실패 원인, 체크리스트(stage + stage 전후 의존성 버전 변경), 설정 키 변경, 레시피 변경
 │    ├─ Record         NN-stage/result.md, result.json, cumulative.patch, stage.patch, result.html
 │    └─ Commit         --commit 일 때만. 게이트를 통과한 stage 만, 추적 중인 파일의 변경과 레시피가 만든 파일만 담는다
 │
 │    다음 중 하나면 그 stage 에서 멈추고 커밋하지 않는다
 │      컴파일 실패, 새로 실패한 테스트(다시 돌려도 실패), 원본 빌드에서는 실패하지 않던 태스크의 실패, 원인을 모르는 빌드 실패,
 │      읽지 못한 테스트 결과 XML
 │    고치고 같은 명령을 다시 실행하면 그 stage 의 게이트를 처음부터 다시 확인하고, 통과하면 커밋하고 다음 stage 로 간다
 │    (컴파일 에러를 고치지 않고 다시 실행하면 stage 별 누적 patch 로 그 stage 전 상태를 만들어 그 stage 부터 다시 시도)
 │
 └─ 5) 완료 ──────────── history.md 에 실행 기록을 남기고 run-state.json 을 지운다
```

`--mode=all` 이면 목표까지의 stage 레시피를 이어 한 번에 적용하고 게이트는 마지막에 한 번만 돈다.
`--mode=preview` 는 소스를 바꾸지 않는다. git 저장소면 임시 worktree 에 stage 를 차례로 적용해 모든 stage 의 `preview.patch` 를 남기고
(컴파일, 테스트 없음, 커밋되지 않은 변경은 빠짐), 아니면 첫 stage 만 본다.

재개 기록(`run-state.json`, `schema/run-state.schema.json`)이 다른 프로젝트의 것이거나, 멈춘 stage 의 누적 patch 가 없거나,
시작한 커밋이 지금 HEAD 의 조상이 아니면 이어서 하지 않고 이유를 알려 준다.

원본에서도 실패하던 태스크와 테스트는 시작할 때 원본 빌드로 기록해 두고 stage 의 실패로 보지 않는다.
테스트 결과는 빌드 전에 결과 XML 의 상태를 떠 두고, 빌드 뒤에 새로 생기거나 바뀐 파일만 그 빌드의 결과로 읽는다.
결과 XML 은 `build/test-results` 와 verify.init.gradle 이 알려 주는 Test 태스크의 junitXml 디렉토리에서 찾는다. 테스트 태스크가
돌았는데 결과 XML 이 하나도 없으면 결과를 못 찾은 것으로 보고 stage 를 막는다.

누적 patch 의 기준은 실행을 시작한 시점의 작업 트리다(`refs/spring-boot-migrator/start` 로 잡아 둔다). `--allow-dirty` 로 시작해도 첫 stage 를
되돌릴 때 기존 변경이 지워지지 않는다. preview 는 기준을 남기지 않고, 새 실행은 지난 실행의 기준을 쓰지 않는다.
재개로 통과한 stage 는 고친 내용까지 담아 patch 를 다시 만든다.

같은 프로젝트에서 `migrationRun` 을 동시에 실행하면 두 번째 실행은 `.spring-boot-migrator/.lock` 에 막혀 바로 멈춘다.
`--commit` 에서 커밋이 실패하면(pre-commit hook 등) `NN-stage/commit.log` 를 남기고 멈춘다.

## Output Files

모두 대상 프로젝트의 `.spring-boot-migrator/` 에 남는다. `clean` 에 지워지지 않도록 build 밖에 두고, git 에서는 뺀다.

```
.spring-boot-migrator/
  result.html          전 stage 를 한 페이지로 보는 결과. 브라우저로 바로 연다
  history.md           실행마다 시작, 목표, stage 별 결과 한 줄
  run-state.json       재개 기록 (끝까지 마치면 지운다)
  start/               시작할 때 모은 의존성 버전, detect 결과, 원본 빌드 로그
  scan/                migrationScan 결과
  verify/              migrationVerify 로그
  03-boot-3.4/
    result.md          stage 결과 (마크다운)
    result.json        result.html 이 읽는 데이터 (schema/result.schema.json)
    cumulative.patch   시작 시점 대비 누적 변경. 재개할 때 stage 전 상태로 되돌리는 데도 쓴다
    stage.patch        이 stage 에서만 바뀐 diff
    preview.patch      --mode=preview 로 만든 변경
    assembled.yml      조립해 돌린 레시피
    rewrite.log, compile.log, build.log, deprecations.log, retry-N.log
```

## Tasks And Options

| 태스크 | 하는 일 |
|---|---|
| `migrationScan` | 현재 Boot / Gradle / Java, resolve 된 의존성, detect 레시피가 찾은 위치 (소스는 그대로) |
| `migrationPlan` | 실행할 stage, 호환성 판단 근거, stage 별 체크리스트 미리보기 (대상 Gradle 을 띄우지 않음) |
| `migrationRun` | stage 별 마이그레이션 |
| `migrationVerify` | 현재 소스의 컴파일 + 전체 테스트 (소스는 그대로) |
| `migrationHelp` | 태스크와 옵션 안내 |

| 옵션 | 태스크 | 기본 | |
|---|---|---|---|
| `--project=<경로>` | 전부 | (필수) | 대상 프로젝트. 상대 경로는 명령을 실행한 위치 기준 |
| `--config=<파일>` | 전부 | 대상 프로젝트의 `spring-boot-migrator.yml` | 설정 파일. 없으면 기본값 |
| `--boot=<값>` | Plan, Run | 4.1 | 3.0 ~ 3.5, 4.0, 4.1 (`3.4.5` 처럼 patch 까지 적으면 minor 로 맞춘다). 현재 Boot 가 목표보다 높으면 아무것도 하지 않는다 |
| `--java=<값>` | Plan, Run | `latest` | `latest`(목표 Boot 가 지원하는 가장 높은 LTS: 3.0 ~ 3.4 는 21, 3.5 ~ 4.1 은 25), `keep`(목표 Boot 가 지원하면 유지), `17` `21` `25`, `none`. 이미 그 이상이면 건너뜀. 목표 Boot 지원 범위 밖이면 거부. 필요한 Gradle 은 Java stage 앞에서 함께 올린다 |
| `--mode=<값>` | Plan, Run | `staged` | `staged`(stage 마다 게이트), `all`(목표까지 한 번에 적용하고 게이트 한 번), `preview`(소스를 바꾸지 않고 patch 만) |
| `--gate=<값>` | Run, Verify | `build` | `compile` / `build` (전체 테스트 + 패키징, asciidoctor, checkstyle 등) / `none` (Run 만) |
| `--commit` | Run | 커밋 안 함 | 게이트를 통과한 stage 마다 commit (작업 트리가 깨끗해야 함) |
| `--allow-dirty` | Run | | 커밋되지 않은 변경이 있어도 시작 (기본은 중단. 재개와 preview 는 검사하지 않음) |

## Config File

자주 바꾸지 않는 값은 대상 프로젝트 루트의 `spring-boot-migrator.yml` 에 둔다. 모든 값은 선택이고, CLI 옵션이 파일보다 우선한다.
파일과 CLI 를 합친 값을 `schema/config.schema.json` 으로 검증한다 (IDE 는 첫 줄의 `$schema` 로 자동완성).

```yaml
# yaml-language-server: $schema=<spring-boot-migrator 경로>/schema/config.schema.json
target:
  boot: "4.1"
  java: latest
mode: staged
gate:
  level: build
  testRetries: 1          # stage 에서 새로 실패한 테스트를 다시 돌리는 횟수. 0 이면 끔
  baselineTests: true     # false 면 원본 빌드에서 테스트를 건너뛴다. stage 의 테스트 실패는 모두 새 실패로 본다
recipes:
  custom: true            # false 면 upstream 만 (비교용). 프로젝트 레시피도 붙이지 않는다
  project: true           # false 면 .rewrite/ 레시피를 붙이지 않는다
build:
  jdk: auto               # current 면 JDK 자동 선택을 끄고 지금 JAVA_HOME 으로
  jvmArgs: "-Xmx4g"       # 대상 Gradle 데몬 JVM 옵션. 비우면 대상의 org.gradle.jvmargs 에서 -Xmx(장비 메모리의 절반, 최대 6g)와 MaxMetaspaceSize 만 바꾼다
  timeoutMinutes: 180     # 대상 Gradle 한 번 실행의 제한 시간. 0 이면 제한 없음
commit: false
allowDirty: false
```

## Project Recipes

대상 프로젝트에만 필요한 보정은 그 프로젝트의 `.rewrite/*.yml` 에 선언형 레시피로 둔다. tags 로 붙일 stage 와 순서를 정한다.
stage 값은 `3.0` ~ `4.1`, `java17`, `java21`, `java25`, `gradle`(모든 Gradle stage), `*`(모든 stage) 중 하나다. 형식이 틀린 태그
(`migration-stage:4.0.x`, `migration-order:befor`)는 파일 이름과 함께 실패한다.

```yaml
type: specs.openrewrite.org/v1beta/recipe
name: com.example.FixLegacyClient
tags:
  - migration-stage:4.0
  - migration-order:before     # stage 레시피보다 먼저. 없으면 after
recipeList:
  - org.openrewrite.java.ChangeType:
      oldFullyQualifiedTypeName: com.example.legacy.Client
      newFullyQualifiedTypeName: com.example.client.Client
```

러너가 stage 마다 `.rewrite/rewrite.assembled.yml` 을 만들어 적용한다. 끄려면 설정 파일의 `recipes.project: false`.

## Reading The Result

`result.html` 은 stage 마다 세 탭으로 보여 준다.

| 탭 | 내용 |
|---|---|
| 확인할 것 | 멈춘 이유(컴파일, 새 테스트 실패, 새 태스크 실패), 체크리스트, 설정 키 변경, 대체 레시피가 없는 제거 예정 API |
| 변경 사항 | 레시피 변경(custom 레시피별, upstream 만 바꾼 파일, deprecated API 대체 레시피)과 git diff 처럼 보는 파일별 변경. 파일 목록, 추가/삭제 줄 수, 한 줄 보기와 나란히 보기, 바뀐 글자 강조, 파일 이름으로 거르기 |
| 참고 | 의존성 버전 변경, detect 검색 결과, deprecated API 사용, 공식 가이드 |

stage 결과(`NN-stage/result.md`)의 섹션과 출처다.

| 섹션 | 출처 | 내용 |
|---|---|---|
| 컴파일 / 테스트 / 빌드 | Gradle | 이 stage 에서 깨진 것 |
| 레시피 변경 | rewriteRun 로그 | 어떤 custom 레시피가 어떤 파일을 바꿨는지, upstream 만 바꾼 파일 (전체 변경은 stage.patch) |
| deprecated API 대체 | `guides/` 의 `deprecations` | 컴파일 경고의 deprecated API 를 바꾼 대체 레시피 |
| 설정 키 변경 | spring-boot-properties-migrator | 이름이 바뀌었거나 없어진 설정 키 |
| 제거 예정 API (`[removal]`) | javac | 대체 레시피가 없어 남은 것. 다음 stage 에서 깨질 곳 |
| 체크리스트 | `guides/` | 컴파일/테스트가 통과해도 확인할 항목. 사람이 처리 / 레시피가 바꿨지만 확인 / 레시피가 고침 으로 나눠 보여준다 |
| 검색 결과 (detect) | `detect.ManualMigrationItems` | 자동으로 바꾸지 않는 코드를 검색으로 찾은 위치. 문제인지는 판정하지 않는다 |

**spring-boot-properties-migrator** 는 모든 Boot stage 레시피가 `runtimeOnly` 로 추가한다. Spring 컨텍스트가 뜰 때 이름이 바뀌었거나
없어진 설정 키를 WARN 으로 알려준다. OpenRewrite 는 저장소 안 yml 만 고칠 수 있으므로 외부 설정 저장소(Spring Cloud Config, Vault, Secrets Manager 등) 의 설정은 이걸로 확인한다.
개발/스테이징 배포 후 기동 로그에서 `The use of configuration keys that` 를 검색하고, 정리되면 의존성을 제거한다.

## What Is Not Automated

결과의 "확인할 것" 에 위치와 함께 나온다.

| 대상 | 이유와 확인할 것 |
|---|---|
| mariadb-java-client 2.x | 자동으로 올리지 않는다. 3.x 는 `jdbc:mariadb:aurora://` 스킴 제거와 failover 설정 변경이 있어 JDBC URL 검토가 필요하다 |
| Elasticsearch / OpenSearch 클라이언트 | elasticsearch-java 는 Boot BOM 버전을 따라 올라가고(3.4 에서 8.15, 4.0 에서 9.x), 3.x 동안은 저수준 RestClient(HttpClient 4)를 유지하고 4.0 에서 `Rest5Client`(HttpClient 5)로 옮긴다. opensearch-java 는 레시피가 3.x 로 올린다. 서버 버전 호환과 Jackson 3 날짜 형식 변화는 개발 환경에서 확인한다 |
| redisson-spring-data-XX, Sentry, playtika embedded-* | Boot 버전에 1:1 로 묶인 artifact 라서 이름 자체가 바뀐다 |
| spring-retry 에서 Spring Framework 7 core retry 로 | 4.0 에서 레시피는 `spring-retry` 2.0.x 를 선언해 기존 코드를 유지한다. core retry 는 API 가 달라(재시도 횟수 계산, checked `RetryException`, 리스너에 재시도 횟수 없음) 직접 옮긴다. 사용처는 검색 결과에 표시된다 |
| Hibernate 6.6 검증 강화 | 저장 안 된 엔티티를 참조하면 `TransientObjectException`. cascade 와 저장 순서는 도메인 판단이다 |
| JSON 컬럼 안의 `LocalDate` | Hypersistence 자체 ObjectMapper 로 저장 형식이 바뀔 수 있어 기존 데이터 형식을 확인한다 |
| 배포 이미지와 CI 의 JDK | Java stage 후 Dockerfile 베이스 이미지 등 저장소 밖 설정은 직접 바꾼다 |
| Boot 4.1 과 Spring Cloud | 2025.1.2 이상이 필요하다 (레시피가 2025.1.x 최신으로 올린다) |
| 대체 레시피가 없는 deprecated API | 결과에 위치만 남긴다. 대체 레시피가 생기면 `guides/` 의 `deprecations` 에 추가한다 |

## Manual Review Items

`detect/manual-items.yml` 의 `detect.ManualMigrationItems` 가 검색을 모두 묶는다. 컴파일과 테스트가 통과해도 동작이 바뀔 수 있는 곳은
같은 파일에 이름 있는 검색 레시피로 두고(`FindSpyStubbingThroughCachingProxy` 는 Java 레시피), 아래 표처럼 가이드 체크리스트 항목과 짝을 짓는다.
scan 과 preview 에서 후보 위치를 표시하고, stage 별 영향과 공식 출처는 가이드 체크리스트 항목(`fix: manual`, `detect`)으로 제공한다.
이 검색 레시피들은 소스를 자동 수정하지 않는다. 변환 결과가 분명한 두 가지(3.4 조건부 빈의 반환 타입, 4.0 `@Bean ObjectMapper` 반환 타입)는
stage 레시피가 고친다.

| stage | 검토 대상 | 확인할 회귀 동작 |
|---|---|---|
| 3.0 | 수동 로그인 SecurityContext 저장 | 동일 세션의 다음 요청도 인증 유지 |
| 3.0 | SPA CSRF | 최초 접근, 로그인, 로그아웃 후 POST |
| 3.2 | 전역 예외 처리와 NoResourceFoundException | 없는 URL 은 404 응답 |
| 3.2 | 비동기 캐시 | 캐시 적중, 완료, 오류 시 동작 |
| 3.2 | 트랜잭션 이벤트 리스너 | 기동 및 commit/rollback 별 저장 |
| 3.2 | 요청 본문 버퍼링과 Content-Length | Content-Length 를 요구하는 서버로의 요청 |
| 3.4 | 조건부 ComponentScan | 조건에 따른 컨텍스트 기동 |
| 3.4 | 캐시 프록시를 거치는 spy stubbing | stubbing 한 값이 캐시되지 않고 spy 에 닿는지 |
| 4.0 | Redis JSON serializer | 기존 데이터, 타입 정보, null 값 호환 |
| 4.0 | HttpHeaders / MultiValueMap | 타입 호환 및 헤더 대소문자 의미 |
| 4.0 | TestExecutionListener | 최상위, 중첩 테스트 초기화 |
| 4.0 | HttpMessageConverter 빈 | 직접 등록한 컨버터로 JSON 이 나가고 들어오는지 |
| 4.0 | OpenFeign 과 Boot 컨버터 customizer | Feign 응답 역직렬화의 JSON 설정 |

검색 결과는 결함 확정이 아니다. 일부 레시피는 파일 단위 조합을 검색하므로 서로 무관한 선언이
같은 파일에 있으면 후보가 될 수 있다. 어노테이션 배열, 생략된 예외 타입, 다른 파일의 합성 어노테이션,
상속/외부 설정 및 별도 메서드의 저장 흐름을 완전히 분석하지 않는다. 검색은 stage 와 무관하게 후보를
보여주며 stage 별 적용 여부는 체크리스트에서 확인한다. upstream 적용 후에도 남는 사례를
재현한 뒤 자동 보정으로 확장한다.
