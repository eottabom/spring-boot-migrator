package com.eottabom.migration.pipeline.step;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.guide.Deprecation;
import com.eottabom.migration.guide.Guides;
import com.eottabom.migration.plan.Stage;
import com.eottabom.migration.result.CompileWarnings;
import com.eottabom.migration.result.CompileWarnings.ApiWarning;
import com.eottabom.migration.workspace.StageFiles;

/**
 * 컴파일 경고의 deprecated API 와 제거 예정 API 를 guides/ 의 대체 레시피로 바로 바꾼다. 대체 레시피가 없는 API 는 결과에 위치만
 * 남긴다.
 */
public record DeprecationStep(Guides guides, RewriteStep rewrite, RunnerConsole console) {

	/**
	 * 컴파일이 통과한 뒤 부른다. 바꾼 게 있으면 호출하는 쪽이 다시 컴파일한다.
	 * @return 적용한 대체 레시피. 없으면 빈 목록
	 */
	public Fixed fix(Stage stage, String tag, StageFiles files, Path projectDir) {
		List<String> messages = warningMessages(files.compileLog(), projectDir);
		Set<String> recipes = new LinkedHashSet<>();
		for (Deprecation deprecation : this.guides.deprecations(stage.covers())) {
			if (messages.stream().anyMatch(deprecation::matches)) {
				recipes.add(deprecation.recipe());
			}
		}
		if (recipes.isEmpty()) {
			return Fixed.NONE;
		}
		this.console.heading("[" + stage.name() + "] deprecated API 대체 " + String.join(", ", recipes));
		move(files.compileLog(), files.compileBeforeDeprecationsLog());
		Set<String> created = this.rewrite.runRecipes(
				"migration.assembled.Deprecations_" + tag.replaceAll("[^A-Za-z0-9]", "_"), List.copyOf(recipes),
				files.deprecationsLog());
		return new Fixed(List.copyOf(recipes), created);
	}

	private static List<String> warningMessages(Path compileLog, Path projectDir) {
		CompileWarnings warnings = CompileWarnings.collect(compileLog, projectDir);
		return Stream.concat(warnings.removal().stream(), warnings.deprecation().stream())
			.map(ApiWarning::message)
			.toList();
	}

	private static void move(Path from, Path to) {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * @param recipes 적용한 대체 레시피
	 * @param createdFiles 대체 레시피가 새로 만든 파일
	 */
	public record Fixed(List<String> recipes, Set<String> createdFiles) {

		static final Fixed NONE = new Fixed(List.of(), Set.of());

		public boolean applied() {
			return !this.recipes.isEmpty();
		}

	}

}
