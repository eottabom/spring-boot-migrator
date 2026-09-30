package com.eottabom.migration.gradle;

import java.nio.file.Path;

/**
 * 대상 프로젝트의 Gradle 에 붙이는 것들. 대상의 빌드 파일을 건드리지 않으려고 실행할 때만 붙인다.
 *
 * @param rewriteInit init/rewrite.init.gradle (OpenRewrite 플러그인과 레시피 jar)
 * @param verifyInit init/verify.init.gradle (컴파일 경고, 테스트 결과 XML, 의존성 버전, 실패 태스크 기록)
 * @param recipeLibs recipes/build/recipe-libs (레시피 jar 와 의존 jar)
 */
public record InitScripts(Path rewriteInit, Path verifyInit, Path recipeLibs) {
}
