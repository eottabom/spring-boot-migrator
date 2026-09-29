package com.eottabom.migration.recipe;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.eottabom.migration.plan.Stage;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * 대상 프로젝트가 가진 OpenRewrite 선언형 레시피(개발자 관리). 러너는 이 파일들을 고치지 않고 읽기만 한다.
 *
 * <pre>
 * &lt;project&gt;/
 *   rewrite.yml                 OpenRewrite 기본 위치 (있으면)
 *   .rewrite/rewrite.yml
 *   .rewrite/custom/**.yml
 * </pre>
 *
 * 마이그레이션 stage 에 붙일 레시피는 OpenRewrite 표준 필드인 tags 로 표시한다. <pre>
 * tags:
 *   - "migration-stage:3.4"      이 stage 에서 실행 (3.0 ~ 4.1 | java21 | java25 | gradle | *)
 *   - "migration-order:before"   stage 레시피보다 먼저 (기본 after)
 * </pre> 태그가 없는 레시피는 부품으로 보고, 다른 레시피가 참조할 때만 쓰인다.
 */
public record ProjectRecipes(List<Path> files, List<Map<String, Object>> documents, List<ProjectRecipe> recipes) {

	static final String RECIPE_TYPE = "specs.openrewrite.org/v1beta/recipe";

	private static final String STAGE_TAG = "migration-stage:";

	private static final String ORDER_TAG = "migration-order:";

	/** stage 키: Boot minor(3.4), java stage (java21), gradle, 모든 stage (*) */
	private static final Pattern STAGE_KEY = Pattern.compile("\\*|\\d+\\.\\d+|java\\d+|gradle");

	public static ProjectRecipes none() {
		return new ProjectRecipes(List.of(), List.of(), List.of());
	}

	public static ProjectRecipes discover(Path projectDir) {
		List<Path> files = new ArrayList<>();
		addIfFile(files, projectDir.resolve("rewrite.yml"));
		addIfFile(files, projectDir.resolve(".rewrite/rewrite.yml"));
		Path custom = projectDir.resolve(".rewrite/custom");
		if (Files.isDirectory(custom)) {
			try (Stream<Path> walk = Files.walk(custom)) {
				walk.filter((p) -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
					.sorted()
					.forEach(files::add);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		List<Map<String, Object>> documents = new ArrayList<>();
		List<ProjectRecipe> recipes = new ArrayList<>();
		for (Path file : files) {
			for (Map<String, Object> doc : load(file)) {
				documents.add(doc);
				if (RECIPE_TYPE.equals(doc.get("type")) && doc.get("name") != null) {
					tagged(String.valueOf(doc.get("name")), file, doc.get("tags")).ifPresent(recipes::add);
				}
			}
		}
		return new ProjectRecipes(List.copyOf(files), List.copyOf(documents), List.copyOf(recipes));
	}

	/** 이 stage 들에 붙일 레시피. 여러 stage 에 붙은 레시피도 한 번만 */
	public List<String> names(List<String> stageNames, Order order) {
		return this.recipes.stream()
			.filter((recipe) -> recipe.order() == order
					&& stageNames.stream().map(Stage::projectTag).anyMatch(recipe::appliesTo))
			.map(ProjectRecipe::name)
			.toList();
	}

	private static Optional<ProjectRecipe> tagged(String name, Path file, @Nullable Object tags) {
		if (!(tags instanceof List<?> list)) {
			return Optional.empty();
		}
		Set<String> stages = new LinkedHashSet<>();
		Order order = Order.AFTER;
		for (Object tag : list) {
			String t = String.valueOf(tag).trim();
			if (t.startsWith(STAGE_TAG)) {
				String stage = t.substring(STAGE_TAG.length()).trim();
				// 오타(4.0.x, java-21)는 어느 stage 에도 붙지 않고 조용히 빠지므로 막는다
				if (!STAGE_KEY.matcher(stage).matches()) {
					throw new IllegalArgumentException(file + ": " + name + " 의 " + t
							+ " 는 stage 키가 아니에요 (예: migration-stage:3.4, migration-stage:java21, migration-stage:gradle, migration-stage:*)");
				}
				stages.add(stage);
			}
			else if (t.startsWith(ORDER_TAG)) {
				String value = t.substring(ORDER_TAG.length()).trim();
				try {
					order = Order.valueOf(value.toUpperCase(Locale.ROOT));
				}
				catch (IllegalArgumentException ex) {
					throw new IllegalArgumentException(
							file + ": " + name + " 의 " + t + " 는 migration-order:before 또는 migration-order:after", ex);
				}
			}
		}
		return stages.isEmpty() ? Optional.empty()
				: Optional.of(new ProjectRecipe(name, file, Set.copyOf(stages), order));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> load(Path file) {
		List<Map<String, Object>> docs = new ArrayList<>();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			for (Object doc : new Yaml().loadAll(in)) {
				// 빈 문서(--- 연속 등)는 버린다
				if (doc instanceof Map<?, ?> map) {
					docs.add((Map<String, Object>) map);
				}
				else if (doc != null) {
					throw new IllegalArgumentException(file + ": YAML 문서가 맵이 아니에요");
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (RuntimeException ex) {
			throw new IllegalArgumentException("프로젝트 레시피를 읽지 못했어요: " + file + " (" + ex.getMessage() + ")", ex);
		}
		return docs;
	}

	private static void addIfFile(List<Path> files, Path file) {
		if (Files.isRegularFile(file)) {
			files.add(file);
		}
	}

	public enum Order {

		BEFORE, AFTER

	}

	/**
	 * @param stages 붙일 stage 키. "*" 는 모든 단계
	 */
	public record ProjectRecipe(String name, Path file, Set<String> stages, Order order) {

		public boolean appliesTo(String stageKey) {
			return this.stages.contains("*") || this.stages.contains(stageKey);
		}
	}
}
