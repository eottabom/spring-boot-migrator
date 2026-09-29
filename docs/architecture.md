# Architecture

사용법은 [Usage](usage.md) 를 본다.

## Recipe Namespaces

레시피 이름만 보고 어디서 온 것인지 알 수 있게 네 이름공간으로 나눈다.

| 이름공간 | 내용 | 파일 (`recipes/src/main/resources/META-INF/rewrite/`) |
|---|---|---|
| `org.openrewrite.*` | upstream 원본 | jar |
| `com.eottabom.rewrite.upstream.*` | upstream 에서 파생한 것. 직전 stage 체인을 뺀 사본, catalog 규칙(생성), 3.0 입구와 upstream 에 아직 없는 4.1 의 대체 | `upstream/` |
| `com.eottabom.rewrite.custom.<도메인>` | 직접 만든 보정. 도메인이 없는 것은 `custom.misc` | `custom/<도메인>.yml`, `recipes/src/main/java/.../custom/` |
| `com.eottabom.rewrite.detect.<도메인>` | 직접 만든 검색. 코드를 바꾸지 않고 위치만 표시한다 | `detect/`, `recipes/src/main/java/.../detect/` |
| `com.eottabom.rewrite.stage.*` | stage 레시피. upstream 과 custom 을 순서대로 묶는다 | `stage/` |

## Stage Recipes

stage 마다 레시피가 하나 있다. 러너는 이 레시피를 프로젝트 레시피와 함께 `.rewrite/rewrite.assembled.yml` 로 조립해 돌린다.

```
stage.Boot_3_4                        이 stage 의 변경만
 ├─ upstream.Boot_3_4                 upstream(rewrite-spring) UpgradeSpringBoot_3_4 에서 직전 stage 체인만 뺀 것 = 본체
 │                                    Boot/Cloud 버전, Framework/Security/Data, 프로퍼티 이름 변경 등 대부분의 작업
 ├─ custom.testing.* 등                이 stage 에서만 필요한 보정
 ├─ custom.aws.UpgradeSpringCloudAws_3_3   upstream 에 없는 라이브러리 정렬
 ├─ custom.spring.AddPropertiesMigrator    이 stage 의 설정 키 변경 확인용
 ├─ upstream.catalog.Boot_3_4         위 레시피들의 버전 변경을 gradle/libs.versions.toml 에도 적용 (생성)
 └─ custom.CommonFixes                매 stage 마지막에 도는 공통 보정

stage.Java_21 / Java_25              upstream UpgradeToJavaNN + JaCoCo toolVersion + catalog 규칙
stage.Gradle_8_14 / Gradle_9_1       Gradle wrapper 와 그 Gradle 에서 동작하지 않는 서드파티 플러그인 정렬
```

upstream 이 큰 틀을 맡고, custom 레시피는 **upstream 만으로는 컴파일/테스트가 깨지거나 사람이 고쳐야 하는 틈**을 메운다.
(OpenRewrite 는 레시피 실행 전에 프로젝트를 컴파일하므로, 한 stage 가 깨지면 다음 stage 로 못 간다)
3.0 stage 는 custom 보정 하나(`KeepLegacyIdGeneratorNaming`)가 upstream 보다 먼저 돌아야 해서, 순서를 stage 레시피가 정한다.

실행 방식에 따라 러너가 조립하는 레시피가 다르다.

| 경우 | 조립 |
|---|---|
| 기본 | stage 마다 `stage.X` |
| `--mode=all` | 목표까지의 `stage.X` 를 차례로 이어 한 번에 |
| `recipes.custom: false` (upstream 만, 비교용) | Boot stage 는 `upstream.Boot_X` 와 `upstream.catalog.Boot_X` 만 |

### Why Separate Stage Recipes

upstream `UpgradeSpringBoot_X_Y` 는 첫 항목으로 직전 stage 를 부르고 그 체인이 Boot 2.0 까지 이어진다.
그대로 쓰면 4.0 프로젝트에 4.1 을 돌려도 Boot 2.x 의 best practice 레시피(프로파일별 yml 분리, JUnit4 -> 5 빌드 블록),
이미 지난 4.0 의 Testcontainers 2 전환 등이 다시 돈다. 실제 프로젝트에서 4.1 stage 가 23개 파일을 바꾸고 컴파일이 깨졌고,
stage 레시피로 바꾼 뒤 2개 파일만 바뀌고 통과했다. 3.0 stage 는 2.x 에서 올라오는 입구라 upstream 전체 체인을 그대로 쓴다.

첫 항목(직전 Boot stage)만 빼도 `UpgradeSpringFramework_7_0 → 6_2 → 6_1`, `MigrateToHibernate64 → 63 → 62` 처럼 라이브러리 체인 안에
지난 stage 의 레시피가 남는다. 생성기는 직전 stage 가 실행하는 옵션 없는 레시피(전이 폐포)를 구하고, 그와 겹치는 선언형 레시피를
`upstream.bootX_Y.<이름>` 사본으로 펼쳐 이미 돈 레시피를 뺀다. 뺀 레시피는 사본의 description 에 적는다. 옵션이 있는 레시피(버전 올리기 등)는
이름이 같아도 옵션이 달라 빼지 않는다. `UpstreamStepsUpToDateTests` 가 stage 마다 직전 stage 체인과 겹치지 않는지 확인한다.

upstream stage 레시피(`upstream/boot-steps.yml`)는 rewrite-spring jar 에서 생성한 파일이다.
rewrite-recipe-bom 을 올리면 `UpstreamStepsUpToDateTests` 가 깨지고, `./gradlew :recipes:syncUpstreamSteps` 로 다시 만든다.

### Version Catalog

upstream 의 `UpgradeDependencyVersion`, `UpgradePluginVersion`, `ChangeDependency` 는 빌드 스크립트의 선언만 바꾸고
`gradle/*.versions.toml` 은 건드리지 않는다. catalog 로 Boot 플러그인 버전을 관리하는 프로젝트는 레시피가 돌아도 Boot 버전이 그대로다.
`VersionCatalogStepsGenerator` 가 stage 레시피 트리 전체에서 이 세 종류의 레시피를 모아 `upstream/catalog.yml` 의 규칙으로 옮기고,
`UpgradeVersionCatalog` 가 같은 규칙을 catalog 에 적용한다. 버전은 upstream 과 같은 방식(`DependencyVersionSelector`)으로 대상 프로젝트의 저장소에서 고른다.
upstream 조건(precondition) 안의 규칙은 조건을 함께 넘기고 catalog 레시피가 Gradle 모델로 판단한다.
`ModuleHasPlugin` 은 `when-plugin <id>`, 버전과 scope 가 없는 `ModuleHasDependency` 는 `when-dependency <g:a>` (반전이면
`unless-dependency <g:a>`) 로 붙인다. `DoesNotUseType`, `FindTypes` 처럼 소스를 봐야 하는 조건 안의 규칙은 뺀다. 조건 없이 옮기면
해당하지 않는 프로젝트의 catalog 도 바뀌기 때문이다. 뺀 규칙은 그 stage 레시피의 description 에 "무엇을 무엇으로" 형태로 적는다.
예) 3.1 ~ 4.1 은 `MigrateCommonsLang2Usages` 의 `commons-lang:commons-lang → org.apache.commons:commons-lang3` 가 빠진다.
commons-lang 2 를 쓰는 파일로 한정한 조건 안에 있어서이고, 코드가 쓰는 commons-lang3 / commons-text 는
`DeclareUsedTransitiveDependencies` 가 따로 선언하고, 남는 commons-lang 2 선언은 `RemoveUnusedCommonsLang2` 가 commons-lang 2 를 쓰는
코드가 없을 때만 지운다.

소스 조건 아래에 둔 의존성 레시피는 빌드 스크립트에서도 돌지 않는다 (빌드 스크립트에는 그 타입이 없다).
`RecipeValidationTests` 가 이런 조합을 찾아 막고, 의존성 선언을 다른 곳에서 챙기는 레시피만 이유와 함께 허용한다.
이 파일도 `syncUpstreamSteps` 가 함께 만들고 `UpstreamStepsUpToDateTests` 가 검사한다. stage 레시피를 고친 뒤에도 다시 만든다.

## Project Layout

```
settings.gradle.kts / build.gradle.kts  루트는 소스 없이 모듈을 묶고 migration* 태스크만 붙인다 (빌드 스크립트는 Kotlin DSL)
recipes/                              OpenRewrite 레시피 jar (대상 프로젝트의 rewrite classpath 에 올라간다)
  build.gradle.kts                    레시피 jar 빌드와 recipeLibs 태스크(recipes/build/recipe-libs). JDK 25 로 빌드하되 바이트코드는 17
                                      (레시피 jar 는 대상 프로젝트의 Gradle JVM 안에서 로딩되는데, JDK 17 로 Gradle 을 띄우는 프로젝트가 있다)
  src/main/resources/META-INF/rewrite/
    stage/                            stage 레시피 (boot.yml, java.yml, gradle.yml)
    upstream/                         boot-steps.yml, catalog.yml (생성 파일, ./gradlew :recipes:syncUpstreamSteps), boot.yml (3.0 입구, 4.1 대체)
    custom/                           도메인별 보정 (aws, elasticsearch, gradle, hibernate, kafka, logging, misc, querydsl, search, spring) 과 common.yml
    detect/                           manual-items.yml (수동 검토 대상), runtime-risks.yml (동작이 바뀔 수 있는 곳)
  src/main/java/                      yml(upstream 조합)로는 불가능한 보정과 검색 (custom/, detect/)
  src/test/java/                      레시피 이름/옵션 검증 + Java 레시피 단위 테스트
runner/                               러너 Gradle 플러그인 (루트 빌드가 쓰는 플러그인이라 included build). 패키지는 아래 Runner 참고
guides/                               버전별 가이드 (아래 Guides 참고)
schema/                               guides, 설정 파일(spring-boot-migrator.yml), result.json, run-state.json 의 JSON Schema
init/rewrite.init.gradle              대상 프로젝트에 OpenRewrite 플러그인과 레시피 jar 를 붙이는 Gradle init script
init/verify.init.gradle               컴파일 경고 옵션, 테스트 fail-fast 해제와 결과 XML 강제, resolve 된 의존성 버전 수집
                                      init script 는 대상 프로젝트의 Gradle 안에서 돌아서 Groovy 로 둔다
                                      (Gradle 8.x 의 Kotlin 스크립트 컴파일러는 JDK 25 에서 깨진다)
```

### Init Scripts

대상 프로젝트의 빌드 파일을 건드리지 않기 위해 Gradle init script(`--init-script`)로 필요한 설정을 실행 시점에만 붙인다.

**`init/rewrite.init.gradle`** (rewriteRun / rewriteDryRun 에 사용)

| 설정 | 내용 |
|---|---|
| `initscript { classpath("org.openrewrite:plugin:7.39.0") }` | OpenRewrite Gradle 플러그인을 가져온다. `-PrewritePluginVersion` 으로 변경 |
| `rootProject { plugins.apply(RewritePlugin) }` | 루트 프로젝트에 플러그인 적용. 서브모듈은 플러그인이 알아서 함께 파싱한다 |
| `rewrite(files(fileTree(rewriteRecipeLibs)))` | `recipes/build/recipe-libs/` 의 jar 전부(레시피 jar + upstream 레시피 모듈)를 rewrite classpath 로 쓴다. maven 저장소를 거치지 않는다 |
| `exclusion(...)` | build, generated(QueryDSL Q-class), node_modules 는 파싱하지 않는다 |
| `failOnDryRunResults = false` | dry-run 에서 변경 사항이 있어도 빌드를 실패로 처리하지 않는다 |

실행할 레시피는 `-Drewrite.activeRecipe=` 로 넘긴다 (쉼표로 여러 개).

이 방식은 `rewrite` configuration 이 루트 프로젝트의 의존성 그래프에 보인다. upstream 의 일부 precondition 이 이걸 실제 의존성으로 오인해서(`jackson-module-jaxb-annotations`) 생기는 부작용은 `RemoveSpuriousJaxbApi` 가 되돌린다.

**`init/verify.init.gradle`** (compile, build 게이트에 사용. 대상 빌드 모델이 필요한 것만)

| 설정 | 내용 |
|---|---|
| `JavaCompile` 에 `-Xlint:deprecation -Xlint:removal -Xmaxwarns 10000` | deprecated / 제거 예정 API 사용처를 경고로 남긴다. Deprecation step 과 결과의 "제거 예정 API" 출처 |
| `Test` 에 `ignoreFailures = true`, `failFast = false`, JUnit XML 강제 | 테스트가 실패해도 끝까지 실행하고 결과 XML 을 남긴다. 프로젝트가 fail-fast 를 켜 둬도 검증 때는 끈다 |
| `migrationResolvedVersions` 태스크 | 전 모듈의 runtime/test classpath 에서 resolve 된 `group:artifact=version` 목록. stage 전후 비교와 라이브러리 체크리스트 판단에 쓴다 |

### Stage Result

stage 가 끝나면 러너(`AssessStep`)가 남은 파일을 읽어 결과 모델을 만들고, `RecordStep` 이 `NN-stage/result.md` 와 `result.json`(`schema/result.schema.json`)을 쓴다.
대상 프로젝트의 Gradle 을 다시 띄우지 않는다.

| 입력 | 결과에 쓰는 곳 |
|---|---|
| `NN-stage/compile.log` | `[removal]` / `[deprecation]` 경고 (같은 위치는 한 번만). 대체 레시피가 있는 것은 Deprecation step 이 먼저 바꾼다 |
| build 가 새로 쓴 `TEST-*.xml` | 테스트 수와 실패 원인(가장 안쪽 예외, 스택의 첫 프로젝트 코드 프레임, 가이드의 실패 힌트). 힌트는 예외가 뜻하는 것이나 공식 문서에 적힌 변경만 쓴다. system-out 에서 properties-migrator 의 설정 키 변경 |
| `NN-stage/rewrite.log` | 파일별 레시피 트리에서 말단 레시피를 가장 가까운 custom 레시피에 귀속시킨 "레시피 변경". 귀속되지 않은 파일은 upstream 이 바꾼 것 |
| `start/detect.patch` | `detect.ManualMigrationItems` 의 `~~>` 마커 위치를 "검색 결과" 로 (판정 없이 위치만) |
| stage 전후 `versions.txt` | 의존성 버전 변경 (major / minor / patch) |
| `guides/` | stage 전후 `versions.txt` 로 고른 체크리스트. 버전 정보가 없으면 `when` 조건이 붙은 항목은 고르지 않는다 |

### Recipe Files

| 파일 | 내용 | 코드 변경 |
|---|---|---|
| `stage/boot.yml` | `stage.Boot_3_0` ~ `4_1`. stage 마다 upstream 과 custom 을 어떤 순서로 돌릴지 정의 | 예 |
| `stage/java.yml`, `stage/gradle.yml` | `stage.Java_17/21/25`, `stage.Gradle_8_14/9_1` | 예 |
| `upstream/boot-steps.yml` | upstream `UpgradeSpringBoot_3_1` ~ `4_0` 에서 직전 stage 체인을 뺀 것. 생성 파일 | 예 |
| `upstream/catalog.yml` | `upstream.catalog.Boot_3_0` ~ `4_1`, `Java_21`, `Java_25`, `Gradle_8_14`, `Gradle_9_1`. stage 레시피의 버전 변경을 catalog 규칙으로 옮긴 것. 생성 파일 | 예 |
| `upstream/boot.yml` | `upstream.Boot_3_0` (upstream 체인 전체), `upstream.Boot_4_1` (upstream 에 아직 없는 4.1 의 대체) | 예 |
| `custom/*.yml` | 도메인별 보정과 `custom.CommonFixes` | 예 |
| `detect/manual-items.yml` | `detect.ManualMigrationItems`. 자동으로 바꾸면 위험한 곳(mariadb-java-client 2.x, redisson, Jackson 3 전환 대상, `@EntityGraph` 등) | 아니오 |
| `detect/runtime-risks.yml` | 컴파일과 테스트가 통과해도 동작이 바뀔 수 있는 곳. 설명은 가이드 체크리스트 항목의 `detect` 에 연결한다 | 아니오 |

## Guides

`guides/` 는 공식 마이그레이션 가이드와 릴리스 노트를 러너가 읽을 수 있게 옮긴 것이다. 파일 하나가 버전 하나이고, 파일이 있는 버전이 stage 가 된다.
파일마다 `schema/` 의 JSON Schema 로 검증한다 (IDE 는 파일 첫 줄의 `$schema` 로 자동완성).

```
guides/
  boot/3.0.yml ... 4.1.yml     requirements(지원 Java, Gradle, Framework, Cloud), raises, checklist, failureHints, deprecations
  java/17.yml 21.yml 25.yml    LTS 만. gradle(이 JDK 에 필요한 Gradle), checklist
  gradle/8.14.yml 9.1.yml      checklist
  libraries/*.yml              library(group:artifact) 와 crosses 또는 affected 로 거는 checklist
  common.yml                   모든 stage 에 쓰는 failureHints, deprecations
```

| 필드 | 뜻 |
|---|---|
| `checklist[].fix` | 누가 고치는가. `auto`(레시피가 고침, 결과만 확인, `recipe` 필수) / `assisted`(레시피가 바꿨지만 확인) / `manual`(기본값, 사람이 처리) |
| `checklist[].when.dependencies` | stage 전후 classpath 에 이 의존성 중 하나라도 있을 때만 |
| `checklist[].source` | 적지 않으면 파일의 `source` |
| `checklist[].detect` | 위치를 찾는 detect 레시피 |
| `failureHints` | 테스트 실패의 가장 안쪽 예외에 pattern 이 맞으면 붙는 한 줄. stage 가이드의 힌트 다음에 공통 힌트 |
| `deprecations` | javac 경고 메시지에 pattern 이 맞으면 같은 stage 안에서 recipe 로 바꾼다 |

플래너는 Java 를 목표 Boot 가 지원하는 가장 높은 LTS 까지 올리고, 그 JDK 가 요구하는 Gradle(`java/NN.yml` 의 `gradle`) 보다 낮으면
Java stage 앞에 충분한 가장 낮은 Gradle stage 를 넣는다. Boot 가 요구하는 Gradle 이 부족할 때도 같은 방식으로 Gradle stage 를 넣는다.

## Custom Recipes

비고의 "실측" 은 대상 프로젝트에서 upstream 만으로는 실제로 깨지는 것을 확인한 항목이다.

### Java Recipes

| 레시피 (`recipes/src/main/java/com/eottabom/rewrite/`) | 내용 | 비고 |
|---|---|---|
| `custom/querydsl/QuerydslJakartaClassifier` | `querydsl-apt:${ver}:jpa` → `:jakarta`, `querydsl-jpa` → `::jakarta` | upstream `ChangeDependencyClassifier` 는 `${}` 문자열, 버전 생략 선언을 처리하지 못함. 실측, Q-class 미생성 |
| `custom/querydsl/EnsureQuerydslAptJakartaApis` | APT 용 `jakarta.annotation-api` / `persistence-api` 보장 | upstream 이 javax 코드 기준으로 판단해 삭제하고, `AddDependency` 는 갱신 안 된 Gradle 모델을 봐서 복구 못 함 |
| `custom/gradle/DeclareUsedDependency` | 쓰는데(import, 정적 import, 패키지 전체 이름) 선언이 없는 의존성을 소스셋별 configuration 에 선언 | Boot 2.7 때 transitive 로 들어오던 것이 사라짐. upstream `AddDependency` 는 transitive 로 있으면 건너뜀. 실측, commons-lang3, commons-io |
| `custom/gradle/RemoveDependencyVersion` | 지정 그룹의 `g:a:v` 에서 버전만 제거 (BOM 좌표는 보호) | upstream `RemoveRedundantDependencyVersions` 는 선언 자체를 지움. 실측, Kafka, AWS SDK 버전 혼합 |
| `custom/feign/DisambiguateRetryableExceptionNull` | `new RetryableException(..., null, req)` → `(Long) null` (3.2~) | Feign 12.2+ 생성자 모호성. 실측, 컴파일 에러 |
| `custom/spring/PreserveConditionalBeanReturnType` | (3.4) `@Bean` 메서드의 `@ConditionalOn(Missing)Bean(annotation = ..)` 에 `value = 반환타입.class` 추가 | Boot 3.4 부터 `annotation` 만 주면 반환 타입을 기본 검사 대상으로 쓰지 않는다. 컴파일은 되고 빈 등록 여부만 바뀐다 (3.4 릴리즈 노트) |
| `detect/spring/FindBeanMethodsReturning` | 지정한 타입(하위 타입 포함)을 반환하는 `@Bean` 메서드 표시 (검색 전용) | upstream `FindTypes` 는 타입이 쓰인 모든 곳을 표시하고 하위 타입을 따라가지 않는다 |
| `detect/testing/FindSpyStubbingThroughCachingProxy` | 캐시 어노테이션이 있는 빈을 `@MockitoSpyBean`/`@SpyBean` 으로 stubbing 하는 테스트 필드 표시 (검색 전용) | 프록시를 거친 stubbing 이 기본값을 캐시한다 ([spring-framework#37121](https://github.com/spring-projects/spring-framework/issues/37121)) |
| `custom/spring/RemoveDependsOnDatabaseInitializationFromDataSourceConfig` | `HikariConfig`/`DataSource` 빈의 `@DependsOnDatabaseInitialization` 제거 | upstream Boot 2.5 레시피가 잘못 붙여서 순환 참조. 실측, contextLoads 실패 |
| `custom/hibernate/FixHypersistenceJsonAttributes` | JSON 컬럼 값 객체(와 하위 객체)에 `Serializable`, equals 가 없으면 `@EqualsAndHashCode` | 없으면 저장 시 `NonSerializableObjectException`(실측), equals 가 없으면 트랜잭션마다 유령 UPDATE. Hypersistence `@Type(Json*Type)` 과 `@JdbcTypeCode(SqlTypes.JSON)` 모두 대상 |
| `custom/testing/AddLenientMockitoExtension` | `@Mock` 필드가 있고 `@ExtendWith` 가 없는 테스트에 lenient MockitoExtension (4.0~) | Boot 4 에서 `@Mock` 자동 초기화가 제거됨. upstream 버전은 strict 라 기존 테스트가 깨짐 (실측) |
| `custom/jackson/NarrowJsonMapperBeanReturnType` | (4.0) `JsonMapper` 를 돌려주는 `@Bean ObjectMapper` 메서드의 반환 타입을 `JsonMapper` 로 | Boot 4 는 `JsonMapper` 타입 빈이 있을 때만 자동 설정 매퍼가 물러난다. `@Bean ObjectMapper` 는 무시되고 커스텀 모듈이 적용되지 않는다 ([spring-boot#50870](https://github.com/spring-projects/spring-boot/issues/50870)). 컴파일은 된다 |
| `custom/jackson/FixJacksonIOExceptionCatch` | try 본문이 IOException 을 던지면 `catch (JacksonException \| IOException e)` 로 보완, 안 던지면 multi-catch 에서 IOException 제거 (4.0~) | upstream Jackson 3 전환이 양방향으로 틀림. 실측, IOException catch 누락으로 컴파일 에러, 던지지 않는 IOException 을 catch 해서 컴파일 에러(never thrown) |
| `custom/httpclient/RevertHttpClient5ForElasticsearchRestClient` | (3.0) Elasticsearch `RestClient` 를 쓰는 파일에서 upstream 의 HttpClient 5 전환을 되돌려 HttpClient 4 유지 | Boot 3.x 의 elasticsearch-java 8.x 는 HttpClient 4 기반. upstream `UpgradeApacheHttpClient_5` 는 REST Assured 모듈만 건너뛰고 ES RestClient 는 제외 목록에 없어 바꿔버림. 실측, 컴파일 에러. 4.0 에서 `MigrateToRest5Client` 가 HttpClient 5 기반 `Rest5Client` 로 옮긴다. 되돌릴 때 생성자 타입 정보도 되돌려야 4.0 에서 upstream 이 `HttpHost` 인자 순서를 다시 바꾼다 |
| `custom/httpclient/FixHttpClient5AsyncInterceptors` | (3.0 opensearch, 4.0 Rest5Client) `addInterceptorLast/First` → `addResponseInterceptorLast/First` (요청 인터셉터는 `addRequestInterceptor*`), 인터셉터 람다 `(response, context)` → `(response, entity, context)` | upstream `UpgradeApacheHttpClient_5` 가 타입만 HttpClient 5 로 바꾸고 async 빌더 메서드 이름과 람다 인자 수는 그대로 둔다. opensearch-rest-client 2.x (HttpClient 4) 에서 3.x 로 올리는 코드에 해당 |
| `custom/elasticsearch/MigrateRangeQueryToUntyped` | (3.4) `RangeQuery.Builder` → `UntypedRangeQuery.Builder`, `build()` → `build()._toRangeQuery()` | Boot 3.4 BOM 의 elasticsearch-java 8.15 에서 `RangeQuery` 가 untyped/date/number/term 중 하나를 고르는 구조로 바뀌어 `field`/`gte`/`lte` 가 `UntypedRangeQuery` 로 옮겨짐. 실측, 컴파일 에러. `q.range(r -> r.field(..))` 람다 형태는 바꾸지 않는다 |
| `custom/elasticsearch/Rest5ClientCallbacksToConsumer` | (4.0) `setHttpClientConfigCallback` / `setRequestConfigCallback` 람다 끝의 `return builder;` 제거 (`return builder.setX(..)` 는 호출만 남김) | `Rest5ClientBuilder` 의 콜백은 `Consumer` 라 값을 돌려주면 컴파일 에러 |
| `custom/lombok/CopyJacksonAnnotationsToAccessors` | 루트 `lombok.config` 에 `lombok.copyJacksonAnnotationsToAccessors = true` (없으면 만들고, 있으면 한 줄 추가, 키가 이미 있으면 그대로). 루트 `.gitignore` 가 `lombok.config` 를 무시하면 `!/lombok.config` 를 추가해 커밋되게 한다. Lombok 과 Jackson 어노테이션을 함께 쓰는 프로젝트만 | Lombok 1.18.40 부터 필드의 `@JsonProperty` 를 getter 에 복사하지 않는다([lombok#3978](https://github.com/projectlombok/lombok/issues/3978)). `@JsonProperty("isShow") boolean isShow` 가 JSON 에 `isShow` 와 `show` 로 두 번 나간다. 실측, REST Docs 테스트 실패(3.4, freefair 플러그인 업그레이드로 새 Lombok 적용). 예전 freefair 가 만들던 파일 때문에 `lombok.config` 를 무시하던 프로젝트가 있었다 |
| `custom/gradle/UpgradeVersionCatalog` | upstream 이 빌드 스크립트에 하는 버전 업그레이드와 좌표 변경을 `gradle/*.versions.toml` 에 적용. 버전 키를 다른 항목과 같이 쓰면 새 키를 만들어 나머지는 그대로 둔다 | upstream 은 catalog 를 바꾸지 않는다. 실측, catalog 를 쓰는 프로젝트의 Boot 버전이 올라가지 않음 |
| `custom/gradle/DeclareAddedDependenciesInVersionCatalog` | 레시피가 이번 실행에서 문자열로 추가한 의존성을 version catalog 항목과 접근자로 바꾼다. 대상은 `libs`, 없으면 하나뿐인 catalog(`gradle/deps.versions.toml` 이면 `deps.` 접근자). 원본에 있던 문자열 선언은 그대로 둔다 | upstream 과 커스텀 레시피는 `"group:artifact"` 문자열로 추가해 catalog 프로젝트에서 선언 방식이 섞인다. 빌드 스크립트를 바꾼 다음 사이클에 catalog 를 고친다 |
| `custom/gradle/UpgradeJacocoToolVersion` | `jacoco { toolVersion = "x" }` 를 지정 버전 이상으로 (Java stage) | upstream `UpgradeJaCoCo` 는 의존성만 올림. 구버전 JaCoCo 는 새 Java 클래스 파일을 못 읽음 |

### YAML Recipes (`custom/*.yml`)

| 레시피 | 내용 | 비고 |
|---|---|---|
| `custom.aws.UpgradeSpringCloudAws_*`, `custom.aws.MigrateSpringCloudAws_2_to_3` | Spring Cloud AWS 버전과 artifact 이름 | upstream 에 없음 |
| `custom.aws.UnpinAwsSdkVersions` | AWS SDK 고정 버전 제거 | 실측, `IncompatibleClassChangeError` |
| Kafka / Hibernate / Spring Boot 모듈 고정 버전 제거 (`custom.CommonFixes` 안) | BOM 관리 버전을 따르게 함. buildscript `classpath`(플러그인)는 건드리지 않는다 | 실측, Kafka `NoSuchMethodError`. Hibernate 는 패치 릴리즈 버그 수정(HHH-17294, HHH-18378 등) 반영. Spring Boot 는 upstream 이 test starter 를 추가하며 `:4.0.8` 을 박는 문제 |
| `custom.spring.UnpinSpringRestDocs`, `custom.spring.RemoveSpuriousJaxbApi` | upstream 부작용 되돌리기 | 실측 |
| `custom.misc.UnshadeRelocatedImports` | `org.testcontainers.shaded.*`, logstash 내부 `commons-lang3` 등 relocate 된 패키지 → 원래 라이브러리 | 실측, Testcontainers 2, logstash 8 |
| `custom.logging.FixLogstashDecoratorForJackson3` | decorator 의 `ObjectMapper.enable()` 과 캐스트 제거 | 실측, Jackson 3 컴파일 에러. 캐스트를 남기면 런타임 ClassCastException |
| `custom.hibernate.MigrateWhereToSQLRestriction` | `@Where` → `@SQLRestriction` (3.2~) | 실측, Hibernate 7 에서 제거 |
| `custom.spring.Boot4JpaRelocations`, `custom.spring.Boot4RestDocsModule` | Boot 4 모듈 분리로 옮겨진 클래스와 의존성 | 실측, upstream 이 잘못 옮기거나 누락 (jar 에서 실제 위치 확인) |
| `custom.spring.Boot4TestStartersForMainSources` | (4.0) `@DataJpaTest`, `@AutoConfigureTestDatabase`, `@WebMvcTest`, `TestRestTemplate` 등을 쓰는 소스셋에 upstream 과 같은 test starter 선언 (버전 없음) | upstream `MigrateToModularStarters` 는 `scope: test` 고정이라 테스트 공용 코드를 `src/main` 에 둔 모듈이 빠진다. 실측, 테스트 전용 모듈의 4.0 컴파일 에러 |
| `custom.search.UpgradeOpenSearchJava_3` | (3.0) `opensearch-java`, `opensearch-rest-client` 를 3.x 로 | Boot BOM 이 관리하지 않아서 직접 올린다. rest-client 3.x 는 HttpClient 5 기반이라 upstream 의 HttpClient 5 전환과 같은 stage 에 둔다 |
| `custom.elasticsearch.MigrateToRest5Client` | (4.0) ES `RestClient` → `Rest5Client`, `RestClientTransport` → `Rest5ClientTransport` (HttpClient 5) | Boot 4 BOM 이 elasticsearch-java 9.x 를 관리하고, 9.x 는 저수준 RestClient(HttpClient 4)를 의존성으로 가져오지 않는다. HttpClient 4 코드는 upstream `UpgradeApacheHttpClient_5` 로 바꾸고 남는 차이는 Java 레시피가 맞춘다. 실측, 4.0 컴파일 에러 |
| `custom.elasticsearch.ElasticsearchJava9ApiChanges` | (4.0) `JacksonJsonpMapper` → `Jackson3JsonpMapper`, 응답 36종의 `valueBody()` → 응답별 이름(`AliasesResponse.aliases()` 등) | upstream Jackson 3 전환이 매퍼를 Jackson 3 로 바꾼다. valueBody 대응은 8.18 / 9.2 jar 를 비교해 반환 타입이 같은 메서드로 정했다. 실측, 4.0 컴파일 에러 |
| `custom.kafka.UseJsonMapperForJacksonJsonSerializer` | (4.0) `JacksonJsonSerializer`/`Deserializer` 를 쓰는 파일의 `ObjectMapper` → `JsonMapper` | Spring Kafka 4 는 Jackson 3 `JsonMapper` 만 받는다. Boot 4 가 `JsonMapper` 빈을 등록하므로 주입도 그대로 된다. 실측, 4.0 컴파일 에러 |
| `custom.hibernate.ReplaceAnnotationsQueryHints` | (4.0) `org.hibernate.annotations.QueryHints.*` → 같은 값의 `org.hibernate.jpa.HibernateHints.HINT_*` | Hibernate 7 에서 제거. upstream `MigrateToHibernate70` 에 대체 규칙이 없다. 실측, 4.0 컴파일 에러 |
| spring-retry 선언 (4.0 stage 의 `DeclareUsedDependency`) | (4.0) `org.springframework.retry` 를 쓰는 모듈에 `spring-retry` 2.0.x | Boot 4 BOM 에서 빠져 transitive 로 받던 프로젝트에서 사라진다. 실측, 4.0 컴파일 에러 |
| `custom.hibernate.KeepLegacyIdGeneratorNaming` | 2.x 에서 올 때 Hibernate 시퀀스 이름 legacy 유지 | 예방 |
| `custom.logging.UpgradeLogstashEncoder_7/8/9`, `stage.Gradle_8_14`, `stage.Gradle_9_1` | 라이브러리, 빌드 도구 정렬 | 예방 |
| `custom.spring.Boot3Extras` | REST Docs 3 API, feign 설정 키, resilience4j | 기존 spring-boot-migrate 에서 가져옴 |

### Upstream Recipes Used As Is

| 작업 | upstream 레시피 | 비고 |
|---|---|---|
| Boot stage 본체 | `UpgradeSpringBoot_3_0` ~ `4_0` | 4.1 은 upstream 에 없어서 프로퍼티 레시피와 버전 업그레이드로 구성 |
| Java 버전업 | `UpgradeToJava17` / `UpgradeToJava21` / `UpgradeToJava25` | 빌드 설정, 플러그인, 문법. `stage.Java_21/25` 가 JaCoCo `toolVersion` 을 함께 맞춘다. Gradle 9 용 플러그인(freefair 9.x, sonarqube 7.x, gradle-git-properties 4.x, asciidoctor 4.x)은 앞의 `stage.Gradle_9_1` 이 맞춘다 |
| Jackson 3 코드 전환 | `UpgradeJackson_2_3` | upstream 4.0 의 `UpgradeSpringFramework_7_0` 에 포함되어 4.0 stage 에서 함께 된다 |
| deprecated API 대체 | `UseLocaleOf`, `URLConstructorToURICreate`, `PrimitiveWrapperClassConstructorToValueOf`, `AuthorizeHttpRequests`, `UseNewRequestMatchers`, `HttpSecurityLambdaDsl` 등 | `guides/common.yml` 의 `deprecations`. 컴파일 경고에 해당 API 가 있을 때만 Deprecation step 이 돌린다 |

## Writing Java Recipes

OpenRewrite 레시피는 소스를 LST(Lossless Semantic Tree, 타입 정보가 붙은 구문 트리)로 읽고, visitor 로 트리를 바꾼 뒤 원래 포맷을 유지한 채 다시 쓴다.
이 프로젝트의 Java 레시피는 두 가지 형태다.

| 형태 | 흐름 | 해당 레시피 |
|---|---|---|
| `Recipe` | `getVisitor()` 가 파일마다 트리를 돌며 바로 수정 | QuerydslJakartaClassifier, EnsureQuerydslAptJakartaApis, RemoveDependencyVersion, UpgradeJacocoToolVersion, DisambiguateRetryableExceptionNull, RemoveDependsOnDatabaseInitializationFromDataSourceConfig, AddLenientMockitoExtension, FixJacksonIOExceptionCatch, RevertHttpClient5ForElasticsearchRestClient, FixHttpClient5AsyncInterceptors, MigrateRangeQueryToUntyped, Rest5ClientCallbacksToConsumer, PreserveConditionalBeanReturnType, NarrowJsonMapperBeanReturnType, FindBeanMethodsReturning |
| `ScanningRecipe` | 1) `getScanner()` 로 전체 파일을 먼저 훑어 정보 수집 2) `getVisitor()` 에서 그 정보로 수정 | DeclareUsedDependency (Java import 를 모은 뒤 build.gradle 수정), FixHypersistenceJsonAttributes (JSON 속성 타입을 모은 뒤 해당 클래스 수정), CopyJacksonAnnotationsToAccessors (Lombok + Jackson 사용 여부와 lombok.config 존재 여부를 본 뒤 파일 생성 또는 추가), UpgradeVersionCatalog (루트 프로젝트의 저장소 정보를 모은 뒤 catalog 수정), FindSpyStubbingThroughCachingProxy (캐시 어노테이션이 있는 타입을 모은 뒤 테스트의 spy 필드 표시) |

- build.gradle 은 Groovy LST, build.gradle.kts 는 Kotlin LST 로 읽힌다. 둘 다 `J.MethodInvocation` / `J.Literal` 로 보이므로 `JavaIsoVisitor` 로 함께 처리한다 (`GroovyIsoVisitor` 는 kts 를 조용히 건너뛴다). `IsBuildGradle` 로 대상을 제한하고, Gradle 모델(선언된 의존성, configuration)은 `GradleProject` 마커에서 읽는다
- Java 소스는 `JavaIsoVisitor` 로 돈다. 어노테이션 추가는 `JavaTemplate`, 인터페이스 추가는 `ImplementInterface` 를 쓴다
- yml 에서 옵션을 주는 레시피(DeclareUsedDependency, RemoveDependencyVersion)는 생성자 파라미터 이름으로 매핑된다 (`-parameters` 컴파일 옵션)
- 스캔은 편집 전 원본 기준으로 한 번 돈다. 같은 실행 안에서 upstream 이 패키지를 바꾸는 경우(commons-lang → lang3) 바뀌기 전 패키지도 같이 적는 이유다
- 각 클래스의 Javadoc 에 대상 증상과 조건을 적어 두었다. 테스트(`recipes/src/test/java`)가 입력/출력 예시 역할을 한다
- 패키지마다 `package-info.java` 에 `@NullMarked` 를 두고 null 이 될 수 있는 곳에만 `@Nullable` 을 붙인다 (OpenRewrite, Gradle 9 API 와 같은 JSpecify 규칙). `@NullMarked` 패키지에서는 `@Nullable` 이 없는 레시피 옵션이 필수가 된다

## Runner

루트 빌드가 쓰는 Gradle 플러그인이라 included build(`runner/`)로 둔다. 대상 프로젝트는 별도 프로세스로 실행하고, 러너는 그 결과 파일만 읽는다.

| 패키지 | 역할 | 주요 클래스 |
|---|---|---|
| `plugin` | Gradle 어댑터. 옵션을 설정으로 바꿔 넘긴다 | `MigrationPlugin`, `MigrationRunTask`, `MigrationPlanTask`, `MigrationScanTask`, `MigrationVerifyTask`, `MigrationHelpTask` |
| `config` | 설정 파일과 CLI 병합, 스키마 검증 | `MigrationConfig`, `ConfigLoader`, `Mode`, `Gate`, `JavaTarget` |
| `project` | 대상 프로젝트 읽기 | `ProjectInspector`, `ProjectState`, `VersionCatalog`, `JdkLocator` |
| `guide` | guides/ 읽기 | `Guides`, `BootGuide`, `JavaGuide`, `GradleGuide`, `LibraryGuide`, `ChecklistItem`, `FailureHint`, `Deprecation` |
| `plan` | stage 결정 (파일과 프로세스를 다루지 않는다) | `MigrationPlanner`, `MigrationPlan`, `Stage` |
| `recipe` | 대상 프로젝트 레시피 | `ProjectRecipes` (`.rewrite/` 탐색), `AssembledRecipe` (`rewrite.assembled.yml`) |
| `pipeline` | 실행 흐름 | `MigrationRunner` (태스크 진입점), `MigrationPipeline` (한 번의 실행), `StageRunner` (stage 의 step 순서), `Resumption` (재개), `RunSession`, `RunHistory`, `StagePreview`, `RunLock` |
| `pipeline.step` | stage 안의 동작 | `RewriteStep`, `GateStep`, `DeprecationStep`, `AssessStep`, `RecordStep`, `CommitStep`, `BaselineBuild`, `FlakyTestRetry`, `TestRun`, `StagePatches` |
| `gradle` | 대상 빌드 실행 | `BuildTool`, `ProjectGradle` (gradlew 프로세스, 제한 시간), `VerifyScript`, `FailedTasks` |
| `result` | 결과 | `StageResult`, `StageSummary`, `ResultMarkdown`, `ResultHtml`, `TestReport`, `TestResults`, `CompileWarnings`, `RecipeChanges`, `DependencyChanges` |
| `workspace` | `.spring-boot-migrator/` 와 git | `MigrationWorkspace`, `StageFiles`, `ProjectFiles`, `RunState`, `RunStateStore`, `Git` |
| `console` | 콘솔 출력 | `RunnerConsole` |
| `misc` | 도메인이 없는 도구 | `AtomicFiles`, `Processes`, `TextFiles`, `Versions` |

의존 방향은 `plugin → config, pipeline → step → 도메인 패키지` 한쪽으로만 흐른다. `plan`, `guide`, `result`, `project`, `workspace`, `config` 는
실행 흐름을 모르고, step 은 서로 부르지 않는다. `ArchitectureTests` 가 이 방향을 검사한다.
러너 통합 테스트(`MigrationRunnerFlowTests`)는 `BuildTool` 을 가짜 구현으로 바꿔 실패, 수정, 재개, deprecated API 대체, `--mode=all` 흐름을 재현한다.

## Extending

| 하고 싶은 것 | 방법 |
|---|---|
| 누락 의존성 선언 | `custom/gradle.yml` 의 `DeclareUsedTransitiveDependencies` 에 `DeclareUsedDependency` 한 줄 추가 |
| 3rd-party 버전 정렬 | `custom/<도메인>.yml` 에 레시피를 만들고 `stage/boot.yml` 의 해당 `stage.Boot_X_Y` 에 추가 |
| 새 체크리스트 항목 | 해당 버전의 `guides/boot/X.Y.yml` (라이브러리 버전 조건이면 `guides/libraries/`) 에 추가. 장애/버그 → 원인 → 재현 조건 → 등록 → 가능하면 detect 레시피나 custom 레시피를 만들어 `detect` / `recipe` 에 연결 |
| deprecated API 자동 대체 | 대체 레시피가 있으면 `guides/common.yml` (또는 버전 가이드) 의 `deprecations` 에 javac 경고 pattern 과 recipe 추가 |
| 지원 범위 변경 (Java/Gradle) | `guides/boot/X.Y.yml` 의 `requirements` |
| 새 Java LTS, 새 Gradle stage | `guides/java/NN.yml` 또는 `guides/gradle/X.Y.yml` 과 `stage/java.yml` 또는 `stage/gradle.yml` 의 `stage.Java_NN`, `stage.Gradle_X_Y` |
| rewrite-recipe-bom 버전 올리기 | 올린 뒤 `./gradlew :recipes:syncUpstreamSteps` 로 upstream stage 레시피와 catalog 규칙을 다시 만들고 `./gradlew test` |
| 새 Boot stage | `guides/boot/X.Y.yml`, `stage/boot.yml` 의 `stage.Boot_X_Y`, `UpstreamStepsGenerator` 의 stage 목록 |

`./gradlew test` 는 guides 가 스키마에 맞는지, `recipe` / `detect` 가 가리키는 레시피와 러너가 고르는 stage 레시피가 실제로 있는지까지 검증한다.
한 프로젝트에서만 나온 문제도 공용 레시피로 만든다. 해당 타입이나 의존성이 있을 때만 바뀌도록 조건을 걸어(`UsesType`, 원래 타입의 메서드 확인 등) 다른 프로젝트에는 영향이 없게 한다.
- 추가한 뒤 `./gradlew test` (레시피 이름/옵션 검증) → `migrationRun --mode=preview` 로 대상 프로젝트 확인

### Versions

| 항목 | 위치 | 현재 |
|---|---|---|
| rewrite-recipe-bom | `recipes/build.gradle.kts` | 3.37.0 |
| OpenRewrite Gradle plugin | `init/rewrite.init.gradle` | 7.39.0 |

두 버전은 같은 `rewrite-bom` 을 참조하는 조합으로 맞춘다.
