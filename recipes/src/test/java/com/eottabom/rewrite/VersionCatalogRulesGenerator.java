package com.eottabom.rewrite;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Recipe;
import org.openrewrite.config.DeclarativeRecipe;
import org.openrewrite.config.Environment;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * stage 레시피 트리의 의존성, 플러그인 버전 변경을 UpgradeVersionCatalog 규칙으로 옮긴 upstream/catalog.yml 을
 * 만든다. upstream stage는 rewrite-spring 원본에서 읽어서 syncUpstreamStages 한 번으로 두 파일이 함께 맞춰진다.
 */
public final class VersionCatalogRulesGenerator {

	public static final Path OUTPUT = Path.of("src/main/resources/META-INF/rewrite/upstream/catalog.yml");

	static final String PREFIX = "com.eottabom.rewrite.upstream.catalog.";

	/** stage → 러너가 실행하는 레시피 */
	private static final Map<String, String> STAGES = new LinkedHashMap<>();

	static {
		for (String suffix : BootStages.suffixes()) {
			STAGES.put("Boot_" + suffix, "com.eottabom.rewrite.stage.Boot_" + suffix);
		}
		STAGES.put("Java_21", "com.eottabom.rewrite.stage.Java_21");
		STAGES.put("Java_25", "com.eottabom.rewrite.stage.Java_25");
		STAGES.put("Gradle_8_14", "com.eottabom.rewrite.stage.Gradle_8_14");
		STAGES.put("Gradle_9_1", "com.eottabom.rewrite.stage.Gradle_9_1");
	}

	private VersionCatalogRulesGenerator() {
	}

	public static void main(String[] args) throws IOException {
		Files.writeString(OUTPUT, generate());
		System.out.println("생성: " + OUTPUT);
	}

	static String generate() {
		Environment env = RecipeEnvironment.get();
		List<Object> docs = new ArrayList<>();
		for (Map.Entry<String, String> stage : STAGES.entrySet()) {
			Set<String> rules = new LinkedHashSet<>();
			Set<String> conditional = new LinkedHashSet<>();
			new Walker(env, rules, conditional, new int[] { 0 }, new ArrayDeque<>())
				.walk(env.activateRecipes(stage.getValue()));
			conditional.removeAll(rules);
			Map<String, Object> options = new LinkedHashMap<>();
			options.put("rules", new ArrayList<>(rules));
			Map<String, Object> doc = new LinkedHashMap<>();
			doc.put("type", "specs.openrewrite.org/v1beta/recipe");
			doc.put("name", PREFIX + stage.getKey());
			doc.put("displayName", "version catalog 정렬 (" + stage.getKey() + ")");
			doc.put("description",
					stage.getValue() + " 의 의존성/플러그인 버전 변경을 gradle/*.versions.toml 에 적용한다." + excluded(conditional));
			doc.put("recipeList", List.of(Map.of("com.eottabom.rewrite.custom.gradle.UpgradeVersionCatalog", options)));
			docs.add(doc);
		}
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setAllowUnicode(true);
		options.setWidth(200);
		return "# 생성 파일: ./gradlew syncUpstreamStages (VersionCatalogRulesGenerator). 직접 고치지 않는다.\n" + "---\n"
				+ new Yaml(options).dumpAll(docs.iterator());
	}

	/**
	 * 뺀 규칙을 적는다. upstream 은 "이 타입을 쓰는 소스가 있을 때", "이 버전의 의존성이 있을 때" 같은 조건 안에서만 바꾸는데,
	 * catalog 는 소스와 resolve 결과를 보지 않아 같은 조건을 판단할 수 없다. 조건 없이 옮기면 해당하지 않는 프로젝트도 바뀌므로 뺀다.
	 */
	static String excluded(Set<String> conditional) {
		if (conditional.isEmpty()) {
			return "";
		}
		List<String> readable = conditional.stream().map(VersionCatalogRulesGenerator::readable).toList();
		return " upstream 이 소스나 의존성 조건(precondition)을 걸고 바꾸는 규칙은 catalog 에서 그 조건을 판단할 수 없어 옮기지 않았다: "
				+ String.join("; ", readable) + ". 조건에 해당하는 프로젝트는 catalog 의 이 항목을 직접 고친다.";
	}

	/** UpgradeVersionCatalog 규칙을 읽기 쉽게. 예) change a:b c:d 3.x → a:b 를 c:d 3.x 로 */
	private static String readable(String rule) {
		String[] parts = rule.split(" ");
		return switch (parts[0]) {
			case "change" -> parts[1] + " 를 " + parts[2] + ((parts.length > 3) ? " " + parts[3] : "") + " 로";
			case "dependency" -> parts[1] + " 를 " + parts[2] + " 로";
			case "plugin" -> "플러그인 " + parts[1] + " 를 " + parts[2] + " 로";
			default -> rule;
		};
	}

	/**
	 * @param conditional upstream 조건 안에 있어서 뺀 규칙
	 * @param depth 지나온 조건부 레시피 중 catalog 에서 판단할 수 없는 것의 수 (0 이면 모두 판단할 수 있다)
	 * @param conditions 지나온 조건 중 catalog 레시피가 Gradle 모델로 판단하는 것 (when-plugin,
	 * when-dependency, unless-dependency)
	 */
	private record Walker(Environment env, Set<String> rules, Set<String> conditional, int[] depth,
			Deque<String> conditions) {

		void walk(Recipe recipe) {
			Recipe target = unwrap(recipe);
			String name = target.getName();
			if (name.startsWith(PREFIX)) {
				return;
			}
			String rule = rule(target);
			if (rule != null) {
				String withConditions = this.conditions.isEmpty() ? rule
						: rule + " " + String.join(" ", this.conditions);
				((this.depth[0] == 0) ? this.rules : this.conditional).add(withConditions);
				return;
			}
			List<Recipe> preconditions = preconditions(target);
			if (preconditions.isEmpty()) {
				walkChildren(target);
				return;
			}
			List<String> converted = preconditions.stream().map(Walker::condition).toList();
			// DoesNotUseType, 버전이나 scope 가 걸린 의존성 조건은 소스와 빌드 파일 기준이라 catalog 에서는 판단할 수 없다
			if (converted.contains(null)) {
				this.depth[0]++;
				walkChildren(target);
				this.depth[0]--;
				return;
			}
			converted.forEach(this.conditions::addLast);
			walkChildren(target);
			converted.forEach((condition) -> this.conditions.removeLast());
		}

		private void walkChildren(Recipe parent) {
			String name = parent.getName();
			String version = name.startsWith(UpstreamStagesGenerator.STAGE_PREFIX)
					? name.substring(UpstreamStagesGenerator.STAGE_PREFIX.length()).replace('_', '.') : "";
			// 생성한 upstream stage 는 원본에서 읽는다 (3.0 입구와 4.1 대체 레시피는 손으로 쓴 것이라 그대로 걷는다)
			String upstream = UpstreamStagesGenerator.upstreamOf(version);
			if (upstream != null) {
				String previous = UpstreamStagesGenerator.previousOf(version);
				for (Recipe child : this.env.activateRecipes(upstream).getRecipeList()) {
					if (!unwrap(child).getName().equals(previous)) {
						walk(child);
					}
				}
				return;
			}
			parent.getRecipeList().forEach(this::walk);
		}

		/** catalog 레시피가 판단할 수 있는 조건이면 규칙 뒤에 붙일 문자열, 아니면 null */
		private static @Nullable String condition(Recipe precondition) {
			return switch (precondition.getName()) {
				case "org.openrewrite.gradle.search.ModuleHasPlugin" -> (field(precondition, "pluginClass") == null)
						? "when-plugin " + field(precondition, "pluginId") : null;
				case "org.openrewrite.java.dependencies.search.ModuleHasDependency" -> {
					if (field(precondition, "scope") != null || field(precondition, "version") != null) {
						yield null;
					}
					String coordinates = field(precondition, "groupIdPattern") + ":"
							+ field(precondition, "artifactIdPattern");
					yield Boolean.TRUE.equals(field(precondition, "invertMarking")) ? "unless-dependency " + coordinates
							: "when-dependency " + coordinates;
				}
				default -> null;
			};
		}

		/** Singleton 을 뺀 조건 */
		@SuppressWarnings("unchecked")
		private static List<Recipe> preconditions(Recipe recipe) {
			if (!(recipe instanceof DeclarativeRecipe)) {
				return List.of();
			}
			return ((List<Recipe>) field(recipe, "preconditions")).stream()
				.map(Walker::unwrap)
				.filter((precondition) -> !"org.openrewrite.Singleton".equals(precondition.getName()))
				.toList();
		}

		private static Recipe unwrap(Recipe recipe) {
			if (!recipe.getClass().getName().contains("BellwetherDecorated")) {
				return recipe;
			}
			return (Recipe) field(recipe, "delegate");
		}

		private static @Nullable String rule(Recipe recipe) {
			return switch (recipe.getClass().getName()) {
				case "org.openrewrite.java.dependencies.UpgradeDependencyVersion",
						"org.openrewrite.gradle.UpgradeDependencyVersion" ->
					join("dependency", field(recipe, "groupId") + ":" + field(recipe, "artifactId"),
							field(recipe, "newVersion"), field(recipe, "versionPattern"));
				case "org.openrewrite.gradle.plugins.UpgradePluginVersion" -> join("plugin",
						field(recipe, "pluginIdPattern"), field(recipe, "newVersion"), field(recipe, "versionPattern"));
				case "org.openrewrite.java.dependencies.ChangeDependency",
						"org.openrewrite.gradle.ChangeDependency" ->
					join("change",
							field(recipe, "oldGroupId") + ":" + field(recipe, "oldArtifactId") + " "
									+ or(field(recipe, "newGroupId")) + ":" + or(field(recipe, "newArtifactId")),
							field(recipe, "newVersion"), field(recipe, "versionPattern"));
				default -> null;
			};
		}

		private static @Nullable String join(String kind, Object coordinates, @Nullable Object newVersion,
				@Nullable Object versionPattern) {
			if (newVersion == null && !"change".equals(kind)) {
				return null;
			}
			StringBuilder rule = new StringBuilder(kind).append(' ').append(coordinates);
			if (newVersion != null) {
				rule.append(' ').append(newVersion);
				if (versionPattern != null) {
					rule.append(' ').append(versionPattern);
				}
			}
			return rule.toString();
		}

		private static Object or(Object value) {
			return (value != null) ? value : "*";
		}

		private static Object field(Object target, String name) {
			for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
				try {
					Field field = type.getDeclaredField(name);
					field.setAccessible(true);
					return field.get(target);
				}
				catch (NoSuchFieldException ex) {
					// 상위 클래스에서 찾는다
				}
				catch (IllegalAccessException ex) {
					throw new IllegalStateException(ex);
				}
			}
			throw new IllegalStateException(target.getClass().getName() + " 에 " + name + " 필드가 없다");
		}

	}

}
