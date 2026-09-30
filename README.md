# spring-boot-migrator

<!-- badges:start -->
![runner coverage](https://img.shields.io/badge/runner%20coverage-82.8%25-green) ![recipes coverage](https://img.shields.io/badge/recipes%20coverage-97.4%25-brightgreen)
<!-- badges:end -->

Spring Boot 프로젝트를 명령 하나로 목표 버전(기본 Boot 4.1)까지 stage 별로 올리는 OpenRewrite 레시피와 Gradle 러너.

- upstream OpenRewrite 레시피로 큰 틀을 바꾸고, upstream 만으로는 컴파일이나 테스트가 깨지는 곳을 custom 레시피로 메운다
- stage 마다 컴파일과 전체 테스트를 게이트로 확인하고, 깨지면 그 stage 에서 멈춘다. 고친 뒤 같은 명령을 다시 실행하면 이어서 한다
- Java 는 기본으로 목표 Boot 가 지원하는 가장 높은 LTS 까지 올리고, 그 JDK 에 필요한 Gradle 도 Java stage 앞에서 함께 올린다
- 컴파일 경고의 deprecated API 와 제거 예정 API 는 대체 레시피가 있으면 같은 stage 안에서 바로 바꾼다
- stage 마다 결과(체크리스트, 설정 키 변경, 제거 예정 API, 검색 결과)와 patch 를 남기고, `result.html` 에서 변경 사항을 git diff 처럼 본다

## Quick Start

```bash
git clone https://github.com/eottabom/spring-boot-migrator.git
cd spring-boot-migrator

./gradlew migrationScan --project=~/workspace/my-api                  # 현재 상태와 검색 결과 (소스는 그대로)
./gradlew migrationPlan --project=~/workspace/my-api                  # 실행할 stage 와 레시피
./gradlew migrationRun  --project=~/workspace/my-api --mode=preview   # stage 별로 바뀔 내용만 patch 로
./gradlew migrationRun  --project=~/workspace/my-api                  # 실제로 적용
```

자주 쓰는 조합은 아래와 같다.

```bash
./gradlew migrationRun --project=~/workspace/my-api --boot=3.5 --java=keep   # 3.5 까지, Java 는 3.5 가 지원하면 그대로
./gradlew migrationRun --project=~/workspace/my-api --commit                 # 게이트를 통과한 stage 마다 커밋
./gradlew migrationRun --project=~/workspace/my-api --mode=all               # 목표까지 한 번에 적용하고 게이트 한 번
./gradlew migrationVerify --project=~/workspace/my-api                       # 현재 소스의 컴파일과 전체 테스트
./gradlew migrationHelp                                                      # 태스크와 옵션 안내
```

자주 바꾸지 않는 값(테스트 재시도, 빌드 제한 시간, JDK 선택 등)은 대상 프로젝트의 `spring-boot-migrator.yml` 에 둔다 ([Usage](docs/usage.md#config-file)).
결과는 대상 프로젝트의 `.spring-boot-migrator/` 에 남는다.

## Requirements

- 이 저장소를 실행할 JDK 17 이상 (레시피는 Gradle toolchain 이 JDK 25 로 컴파일한다)
- 대상 프로젝트의 Gradle wrapper. 대상 프로젝트는 별도 프로세스로 자기 Gradle 과 JDK 로 실행한다
- 대상 프로젝트가 선언한 toolchain 버전의 JDK (없으면 현재 `JAVA_HOME`)

배포나 설치 없이 clone 해서 바로 쓴다.

## Supported Versions

| 항목 | 범위 |
|---|---|
| Spring Boot | 시작 2.5 ~ 4.0, 목표 3.0 ~ 4.1 |
| Java | 17, 21, 25 (목표 Boot 가 지원하는 범위, `guides/boot/*.yml`) |
| Gradle | Groovy, Kotlin DSL 빌드 스크립트와 version catalog (`gradle/*.versions.toml`). 필요하면 8.14, 9.1 로 올린다 |

## Test Coverage

`./gradlew coverage` 로 측정하고 이 표를 갱신한다. 리포트는 각 모듈의 `build/reports/jacoco/test/html` 에 있다.

<!-- coverage:start -->
| Module | Line | Branch | Method |
|---|---|---|---|
| runner | 82.8% | 74.1% | 89.7% |
| recipes | 97.4% | 86.7% | 98.6% |
<!-- coverage:end -->

## Documentation

| 문서 | 내용 |
|---|---|
| [Usage](docs/usage.md) | 동작 흐름, 태스크와 옵션, 설정 파일, 프로젝트 전용 레시피, 결과 읽는 법, 자동으로 하지 않는 것 |
| [Architecture](docs/architecture.md) | 레시피 이름공간과 stage 레시피, guides, 모듈과 패키지 구성, custom 레시피 목록, 확장 방법 |

## License

[MIT](LICENSE)
