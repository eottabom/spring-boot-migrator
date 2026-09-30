plugins {
    `java-library`
    id("io.spring.javaformat") version "0.0.47"
    checkstyle
    jacoco
}

base {
    archivesName = "spring-boot-migrator-recipes"
}

group = "com.eottabom.rewrite"
version = "1.0.0"

// ../init/rewrite.init.gradle 의 OpenRewrite 플러그인 버전과 같은 rewrite-bom 을 쓰는 조합으로 맞춘다
val rewriteRecipeBomVersion = "3.37.0"

java {
    // 빌드는 JDK 25 로 하되, 바이트코드는 17 로 낸다.
    // 레시피 jar 는 대상 프로젝트의 Gradle JVM 안에서 로딩되므로, JDK 17 로 Gradle 을 띄우는 프로젝트에서도 읽혀야 한다.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    // yml 의 옵션을 레시피 생성자 파라미터 이름으로 매핑하려면 -parameters 가 필요하다
    options.compilerArgs.add("-parameters")
}

repositories {
    mavenCentral()
    // 테스트에서 실제 Gradle 모델(withToolingApi)을 쓰기 위한 gradle-tooling-api
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}

dependencies {
    // 레시피 jar 를 소비하는 쪽(init script)에서 upstream 레시피까지 함께 resolve 되도록 runtime 의존성으로 노출한다
    api(platform("org.openrewrite.recipe:rewrite-recipe-bom:$rewriteRecipeBomVersion"))
    implementation("org.openrewrite:rewrite-gradle")
    implementation("org.openrewrite:rewrite-groovy")
    implementation("org.openrewrite:rewrite-toml")
    runtimeOnly("org.openrewrite.recipe:rewrite-spring")
    runtimeOnly("org.openrewrite.recipe:rewrite-migrate-java")
    runtimeOnly("org.openrewrite.recipe:rewrite-hibernate")
    runtimeOnly("org.openrewrite.recipe:rewrite-testing-frameworks")
    runtimeOnly("org.openrewrite.recipe:rewrite-java-dependencies")
    runtimeOnly("org.openrewrite.recipe:rewrite-jackson")
    runtimeOnly("org.openrewrite.recipe:rewrite-static-analysis")

    testImplementation("org.openrewrite:rewrite-test")
    // UpstreamStagesGenerator (upstream 레시피 yml 읽기/쓰기)
    testImplementation("org.yaml:snakeyaml:2.6")
    testImplementation("org.openrewrite:rewrite-gradle")
    testImplementation("org.openrewrite.gradle.tooling:model")
    testImplementation("org.gradle:gradle-tooling-api:8.14.3")
    testImplementation("org.openrewrite.recipe:rewrite-spring")
    testImplementation("org.openrewrite.recipe:rewrite-migrate-java")
    testImplementation("org.openrewrite.recipe:rewrite-hibernate")
    testImplementation("org.openrewrite.recipe:rewrite-testing-frameworks")
    testImplementation("org.openrewrite.recipe:rewrite-java-dependencies")
    testImplementation("org.openrewrite.recipe:rewrite-jackson")
    testImplementation("org.openrewrite.recipe:rewrite-static-analysis")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    checkstyle("io.spring.javaformat:spring-javaformat-checkstyle:0.0.47")
}

checkstyle {
    config = resources.text.fromFile(rootProject.file("config/checkstyle/checkstyle.xml"))
    configDirectory = rootProject.layout.projectDirectory.dir("config/checkstyle")
    // 경고를 허용하면 쌓이기만 하고 아무도 보지 않는다
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

// 레시피 jar 와 그 실행에 필요한 jar(upstream 레시피 모듈 등)를 build/recipe-libs 에 모은다.
// ../init/rewrite.init.gradle 이 이 폴더를 대상 프로젝트의 rewrite classpath 로 쓴다. (배포하지 않는다)
tasks.register<Sync>("recipeLibs") {
    group = "build"
    description = "migration 태스크가 쓰는 레시피 classpath 를 build/recipe-libs 에 만든다"
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("recipe-libs"))
}

// upstream(rewrite-spring) UpgradeSpringBoot_X_Y 에서 직전 stage 체인을 뺀 stage 레시피를 다시 만든다 (rewrite-recipe-bom 을 올린 뒤)
tasks.register<JavaExec>("syncUpstreamStages") {
    group = "build"
    description = "upstream-spring-boot-stages.yml 과 upstream/catalog.yml 을 다시 만든다"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.eottabom.rewrite.UpstreamStagesGenerator"
    workingDir = projectDir
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
