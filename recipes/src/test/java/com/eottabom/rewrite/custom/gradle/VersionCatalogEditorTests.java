package com.eottabom.rewrite.custom.gradle;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.semver.LatestRelease;

import static org.assertj.core.api.Assertions.assertThat;

class VersionCatalogEditorTests {

	/** 3.4.x 는 3.4.9, 3.x 는 3.9.0 을 최신으로 본다. unresolvable 은 저장소에 없는 버전이다 */
	private static final VersionCatalogEditor.Resolver RESOLVER = (group, artifact, current, newVersion, pattern,
			plugin) -> {
		if (newVersion.equals("unresolvable")) {
			return null;
		}
		String latest = newVersion.endsWith(".x")
				? newVersion.replaceAll("\\.x$",
						(newVersion.chars().filter((character) -> character == '.').count() > 1) ? ".9" : ".9.0")
				: newVersion;
		return (current == null || new LatestRelease(null).compare(null, current, latest) < 0) ? latest : null;
	};

	@Test
	void parsesRuleKindRegardlessOfDefaultLocale() {
		Locale original = Locale.getDefault();
		// 터키어는 i 의 대문자가 İ 라서 Locale 없이 바꾸면 PLUGIN 이 되지 않는다
		Locale.setDefault(Locale.forLanguageTag("tr"));
		try {
			assertThat(VersionCatalogEditor.Rule.parse("plugin org.springframework.boot 3.4.x").kind())
				.isEqualTo(VersionCatalogEditor.Rule.Kind.PLUGIN);
		}
		finally {
			Locale.setDefault(original);
		}
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void appliesRules(String scenario, String rules, String before, String after) {
		assertThat(VersionCatalogEditor.apply(before,
				Arrays.stream(rules.split(";")).map(VersionCatalogEditor.Rule::parse).toList(), RESOLVER,
				new VersionCatalogEditor.ProjectFacts(Set.of("io.spring.dependency-management"),
						Set.of("org.junit.jupiter:junit-jupiter-api"))))
			.isEqualTo(after);
	}

	@Test
	void updatesSingleQuotedNotationAndPreservesComments() {
		appliesRules("single quoted coordinates",
				"dependency old:keep 2.0;change old:move new:moved 3.0;plugin demo.plugin 4.0", """
						[libraries]
						keep = 'old:keep:1.0' # keep 'old:keep:1.0'
						move = 'old:move:1.0' # move 'old:move:1.0'
						[plugins]
						plugin = 'demo.plugin:1.0' # plugin
						""", """
						[libraries]
						keep = 'old:keep:2.0' # keep 'old:keep:1.0'
						move = 'new:moved:3.0' # move 'old:move:1.0'
						[plugins]
						plugin = 'demo.plugin:4.0' # plugin
						""");
	}

	@Test
	void changesVersionlessSingleQuotedCoordinates() {
		appliesRules("versionless", "change old:api new:api", """
				[libraries]
				api = 'old:api' # 'old:api'
				""", """
				[libraries]
				api = 'new:api' # 'old:api'
				""");
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		return Stream.of(
			Arguments.of(
				"작은따옴표 문자열 표기",
				"dependency org.springframework.cloud:spring-cloud-dependencies 2024.0.x",
				"""
				[versions]
				cloud = '2023.0.3'
				[libraries]
				cloud-bom = { module = 'org.springframework.cloud:spring-cloud-dependencies', version = { ref = 'cloud' } }
				""",
				"""
				[versions]
				cloud = '2024.0.9'
				[libraries]
				cloud-bom = { module = 'org.springframework.cloud:spring-cloud-dependencies', version = { ref = 'cloud' } }
				"""
			),
			Arguments.of(
				"Boot 플러그인 version.ref",
				"plugin org.springframework.boot 3.4.x",
				"""
				[versions]
				spring-boot = "3.3.5" # boot
				[plugins]
				spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
				""",
				"""
				[versions]
				spring-boot = "3.4.9" # boot
				[plugins]
				spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
				"""
			),
			Arguments.of(
				"version = { ref } 표기",
				"dependency org.springframework.cloud:spring-cloud-dependencies 2024.0.x",
				"""
				[versions]
				cloud = "2023.0.3"
				[libraries]
				cloud-bom = { group = "org.springframework.cloud", name = "spring-cloud-dependencies", version = { ref = "cloud" } }
				""",
				"""
				[versions]
				cloud = "2024.0.9"
				[libraries]
				cloud-bom = { group = "org.springframework.cloud", name = "spring-cloud-dependencies", version = { ref = "cloud" } }
				"""
			),
			Arguments.of(
				"플러그인 문자열 표기와 glob",
				"plugin io.freefair.* 8.x",
				"""
				[plugins]
				lombok = "io.freefair.lombok:6.6.3"
				""",
				"""
				[plugins]
				lombok = "io.freefair.lombok:8.9.0"
				"""
			),
			Arguments.of(
				"BOM 라이브러리 inline version",
				"dependency org.springframework.cloud:spring-cloud-dependencies 2024.0.x",
				"""
				[libraries]
				cloud-bom = { module = "org.springframework.cloud:spring-cloud-dependencies", version = "2023.0.3" }
				""",
				"""
				[libraries]
				cloud-bom = { module = "org.springframework.cloud:spring-cloud-dependencies", version = "2024.0.9" }
				"""
			),
			Arguments.of(
				"group / name 표기와 문자열 표기",
				"dependency org.mockito:* 5.x",
				"""
				[libraries]
				mockito-core = { group = "org.mockito", name = "mockito-core", version = "4.11.0" }
				mockito-junit = "org.mockito:mockito-junit-jupiter:4.11.0"
				""",
				"""
				[libraries]
				mockito-core = { group = "org.mockito", name = "mockito-core", version = "5.9.0" }
				mockito-junit = "org.mockito:mockito-junit-jupiter:5.9.0"
				"""
			),
			Arguments.of(
				"이미 높으면 내리지 않고, BOM 관리 항목과 rich version 은 건드리지 않음",
				"dependency org.springframework.boot:* 3.4.x;dependency com.example:* 1.x",
				"""
				[versions]
				boot = "3.5.0"
				[libraries]
				boot-bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "boot" }
				web = { module = "org.springframework.boot:spring-boot-starter-web" }
				pinned = { module = "com.example:lib", version = { strictly = "0.9" } }
				""",
				"""
				[versions]
				boot = "3.5.0"
				[libraries]
				boot-bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "boot" }
				web = { module = "org.springframework.boot:spring-boot-starter-web" }
				pinned = { module = "com.example:lib", version = { strictly = "0.9" } }
				"""
			),
			Arguments.of(
				"좌표 변경은 버전 키를 같이 쓰는 다른 항목을 남기고 새 키를 만든다",
				"change com.fasterxml.jackson.core:jackson-databind tools.jackson.core:* 3.1.x",
				"""
				[versions]
				jackson = "2.15.0"
				[libraries]
				jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
				jackson-annotations = { module = "com.fasterxml.jackson.core:jackson-annotations", version.ref = "jackson" }
				""",
				"""
				[versions]
				jackson = "2.15.0"
				jackson-databind = "3.1.9"
				[libraries]
				jackson-databind = { module = "tools.jackson.core:jackson-databind", version.ref = "jackson-databind" }
				jackson-annotations = { module = "com.fasterxml.jackson.core:jackson-annotations", version.ref = "jackson" }
				"""
			),
			Arguments.of(
				"좌표 변경 대상만 쓰는 버전 키는 그대로 올린다",
				"change com.github.tomakehurst:wiremock-jre8 org.wiremock:wiremock 3.x",
				"""
				[versions]
				wiremock = "2.35.0"
				[libraries]
				wiremock = { module = "com.github.tomakehurst:wiremock-jre8", version.ref = "wiremock" }
				""",
				"""
				[versions]
				wiremock = "3.9.0"
				[libraries]
				wiremock = { module = "org.wiremock:wiremock", version.ref = "wiremock" }
				"""
			),
			Arguments.of(
				"플러그인 조건은 그 플러그인이 있을 때만 적용한다",
				"change org.springframework.boot:spring-boot-starter-web *:spring-boot-starter-webmvc when-plugin io.spring.dependency-management;"
						+ "change org.springframework.boot:spring-boot-starter-aop *:spring-boot-starter-aspectj when-plugin com.example.other",
				"""
				[libraries]
				web = { module = "org.springframework.boot:spring-boot-starter-web" }
				aop = { module = "org.springframework.boot:spring-boot-starter-aop" }
				""",
				"""
				[libraries]
				web = { module = "org.springframework.boot:spring-boot-starter-webmvc" }
				aop = { module = "org.springframework.boot:spring-boot-starter-aop" }
				"""
			),
			Arguments.of(
				"의존성 조건은 모듈의 resolve 된 의존성으로 판단한다",
				"dependency org.junit.jupiter:* 5.x unless-dependency org.testng:testng*;"
						+ "dependency org.mockito:* 5.x when-dependency org.testng:testng*",
				"""
				[libraries]
				junit = { module = "org.junit.jupiter:junit-jupiter", version = "5.1.0" }
				mockito = { module = "org.mockito:mockito-core", version = "4.11.0" }
				""",
				"""
				[libraries]
				junit = { module = "org.junit.jupiter:junit-jupiter", version = "5.9.0" }
				mockito = { module = "org.mockito:mockito-core", version = "4.11.0" }
				"""
			),
			Arguments.of(
				"버전 없는 항목은 좌표만 바꾼다",
				"change javax.servlet:javax.servlet-api jakarta.servlet:jakarta.servlet-api 6.x",
				"""
				[libraries]
				servlet = { module = "javax.servlet:javax.servlet-api" }
				""",
				"""
				[libraries]
				servlet = { module = "jakarta.servlet:jakarta.servlet-api" }
				"""
			),
			Arguments.of(
				"문자열 표기와 group / name 표기의 좌표와 inline 버전을 함께 바꾼다",
				"change javax.annotation:javax.annotation-api jakarta.annotation:jakarta.annotation-api 2.x",
				"""
				[libraries]
				a = "javax.annotation:javax.annotation-api:1.3.2"
				b = { group = "javax.annotation", name = "javax.annotation-api", version = "1.3.2" }
				""",
				"""
				[libraries]
				a = "jakarta.annotation:jakarta.annotation-api:2.9.0"
				b = { group = "jakarta.annotation", name = "jakarta.annotation-api", version = "2.9.0" }
				"""
			),
			Arguments.of(
				"버전 없는 좌표 변경은 버전을 두고, 이미 목표 버전이면 좌표만 바꾼다",
				"change javax.inject:javax.inject jakarta.inject:jakarta.inject-api;"
						+ "change javax.annotation:javax.annotation-api jakarta.annotation:jakarta.annotation-api 2.x",
				"""
				[libraries]
				inject = "javax.inject:javax.inject:1"
				annotation = "javax.annotation:javax.annotation-api:2.9.0"
				""",
				"""
				[libraries]
				inject = "jakarta.inject:jakarta.inject-api:1"
				annotation = "jakarta.annotation:jakarta.annotation-api:2.9.0"
				"""
			),
			Arguments.of(
				"새 좌표에 버전을 찾지 못하면 좌표도 그대로 둔다",
				"change javax.annotation:javax.annotation-api jakarta.annotation:jakarta.annotation-api unresolvable",
				"""
				[libraries]
				a = "javax.annotation:javax.annotation-api:1.3.2"
				""",
				"""
				[libraries]
				a = "javax.annotation:javax.annotation-api:1.3.2"
				"""
			),
			Arguments.of(
				"[libraries] 가 [versions] 보다 앞이고 새 버전 키 이름이 이미 있으면 번호를 붙인다",
				"change com.fasterxml.jackson.core:jackson-databind tools.jackson.core:* 3.1.x",
				"""
				[libraries]
				jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
				jackson-annotations = { module = "com.fasterxml.jackson.core:jackson-annotations", version.ref = "jackson" }
				[versions]
				jackson = "2.15.0"
				jackson-databind = "2.15.0"
				""",
				"""
				[libraries]
				jackson-databind = { module = "tools.jackson.core:jackson-databind", version.ref = "jackson-databind-2" }
				jackson-annotations = { module = "com.fasterxml.jackson.core:jackson-annotations", version.ref = "jackson" }
				[versions]
				jackson = "2.15.0"
				jackson-databind-2 = "3.1.9"
				jackson-databind = "2.15.0"
				"""
			),
			Arguments.of(
				"읽을 수 없는 항목, 주석, 다른 섹션은 건너뛴다",
				"dependency com.example:* 2.x;plugin com.example.* 2.x",
				"""
				[versions]
				rich = { strictly = "1.0" }
				# commented = "com.example:lib:1.0"
				[libraries]
				no-colon = "com.example"
				number = 3
				bad-module = { module = "com.example:a:b" }
				no-name = { group = "com.example" }
				no-group = { name = "lib" }
				no-version = "com.example:lib"
				[plugins]
				no-id = { version = "1.0" }
				bare = "com.example.plugin"
				[bundles]
				all = ["no-version"]
				""",
				"""
				[versions]
				rich = { strictly = "1.0" }
				# commented = "com.example:lib:1.0"
				[libraries]
				no-colon = "com.example"
				number = 3
				bad-module = { module = "com.example:a:b" }
				no-name = { group = "com.example" }
				no-group = { name = "lib" }
				no-version = "com.example:lib"
				[plugins]
				no-id = { version = "1.0" }
				bare = "com.example.plugin"
				[bundles]
				all = ["no-version"]
				"""
			),
			Arguments.of(
				"플러그인 규칙은 라이브러리에, 라이브러리 규칙은 플러그인에 걸리지 않는다",
				"plugin org.example 2.x;dependency org.example:* 2.x",
				"""
				[libraries]
				lib = "org.example:org.example.gradle.plugin:1.0"
				[plugins]
				example = "org.example:1.0"
				other = "com.other:1.0"
				""",
				"""
				[libraries]
				lib = "org.example:org.example.gradle.plugin:2.9.0"
				[plugins]
				example = "org.example:2.9.0"
				other = "com.other:1.0"
				"""
			),
			Arguments.of(
				"의존성이 있으면 unless-dependency 규칙은 적용하지 않는다",
				"dependency org.junit.jupiter:* 5.x unless-dependency org.junit.jupiter:junit-jupiter-api",
				"""
				[libraries]
				junit = { module = "org.junit.jupiter:junit-jupiter", version = "5.1.0" }
				""",
				"""
				[libraries]
				junit = { module = "org.junit.jupiter:junit-jupiter", version = "5.1.0" }
				"""
			)
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("libraries")
	void addsLibrariesAfterTheLastEntry(String scenario, String before, String after) {
		assertThat(VersionCatalogEditor.addLibraries(before,
				Map.of("restclient", "org.springframework.boot:spring-boot-restclient:4.0.7")))
			.isEqualTo(after);
	}

	@Test
	void addsLibraryWithoutVersionAndIgnoresPluginsWithSameCoordinates() {
		assertThat(VersionCatalogEditor.addLibraries("""
				[libraries]
				web = "org.springframework.boot:spring-boot-starter-web"
				[plugins]
				boot = "org.springframework.boot:4.0.7"
				""", Map.of("boot-plugin", "org.springframework.boot:org.springframework.boot.gradle.plugin")))
			.contains("boot-plugin = { module = \"org.springframework.boot:org.springframework.boot.gradle.plugin\" }");
	}

	// @formatter:off
	static Stream<Arguments> libraries() {
		return Stream.of(
			Arguments.of("[libraries] 가 없으면 끝에 만든다 (끝 줄바꿈 있음)",
				"[versions]\nboot = \"4.0.7\"\n",
				"[versions]\nboot = \"4.0.7\"\n\n[libraries]\nrestclient = { module = \"org.springframework.boot:spring-boot-restclient\", version = \"4.0.7\" }\n"),
			Arguments.of("[libraries] 가 없으면 끝에 만든다 (끝 줄바꿈 없음)",
				"[versions]\nboot = \"4.0.7\"",
				"[versions]\nboot = \"4.0.7\"\n\n[libraries]\nrestclient = { module = \"org.springframework.boot:spring-boot-restclient\", version = \"4.0.7\" }"),
			Arguments.of("주석은 마지막 항목으로 보지 않는다",
				"[libraries]\nweb = \"org.springframework.boot:spring-boot-starter-web\"\n# restclient = \"x:y\"\n",
				"[libraries]\nweb = \"org.springframework.boot:spring-boot-starter-web\"\nrestclient = { module = \"org.springframework.boot:spring-boot-restclient\", version = \"4.0.7\" }\n# restclient = \"x:y\"\n"),
			Arguments.of("빈 줄 뒤에 다른 섹션이 오면 마지막 항목 바로 뒤",
				"[libraries]\nweb = \"org.springframework.boot:spring-boot-starter-web\"\n\n[plugins]\n",
				"[libraries]\nweb = \"org.springframework.boot:spring-boot-starter-web\"\nrestclient = { module = \"org.springframework.boot:spring-boot-restclient\", version = \"4.0.7\" }\n\n[plugins]\n"),
			Arguments.of("같은 모듈이 있으면 그대로",
				"[libraries]\nrest = { module = \"org.springframework.boot:spring-boot-restclient\" }\n",
				"[libraries]\nrest = { module = \"org.springframework.boot:spring-boot-restclient\" }\n")
		);
	}
	// @formatter:on

}
