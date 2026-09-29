plugins {
    `java-gradle-plugin`
    id("io.spring.javaformat") version "0.0.47"
    checkstyle
    jacoco
}

// 레시피 jar 와 달리 이 플러그인은 이 저장소의 Gradle JVM 에서만 돈다 (대상 프로젝트 classpath 에 올라가지 않는다)
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

gradlePlugin {
    plugins {
        create("migration") {
            id = "com.eottabom.migration"
            implementationClass = "com.eottabom.migration.plugin.MigrationPlugin"
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // guides/*.yml 과 설정, 결과 JSON. 스키마 검증은 schema/*.schema.json
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.3")
    implementation("com.networknt:json-schema-validator:1.5.9")
    // 대상 프로젝트의 .rewrite/*.yml (여러 문서) 읽기와 쓰기
    implementation("org.yaml:snakeyaml:2.4")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    // 패키지 의존 방향 (ArchitectureTests)
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    checkstyle("io.spring.javaformat:spring-javaformat-checkstyle:0.0.47")
}

checkstyle {
    config = resources.text.fromFile(file("../config/checkstyle/checkstyle.xml"))
    configDirectory = layout.projectDirectory.dir("../config/checkstyle")
    // 경고를 허용하면 쌓이기만 하고 아무도 보지 않는다
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.test {
    useJUnitPlatform()
}

// JDK 25 에서 테스트를 돌리므로 Java 25 클래스 파일을 읽을 수 있는 버전이 필요하다
jacoco {
    toolVersion = "0.8.14"
}

// 테스트가 끝나면 커버리지 리포트를 만든다 (build/reports/jacoco/test/html/index.html)
tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}
