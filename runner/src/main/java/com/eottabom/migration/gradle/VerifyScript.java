package com.eottabom.migration.gradle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * init/verify.init.gradle 을 붙인 Gradle 인자. 컴파일 경고, 테스트 결과 XML, 의존성 버전, 실패 태스크 기록을 켠다.
 */
public record VerifyScript(Path path) {

	public List<String> args(String... args) {
		List<String> all = new ArrayList<>(List.of("--init-script", this.path.toString()));
		all.addAll(List.of(args));
		return all;
	}

}
