package com.eottabom.rewrite;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.jspecify.annotations.Nullable;
import org.openrewrite.java.spring.boot3.AddRouteTrailingSlash;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * upstream UpgradeSpringBoot_X_Y 에서 직전 stage 체인을 뺀 stage 레시피를 upstream/boot-stages.yml 로
 * 만든다. 체인을 그대로 쓰면 이미 지난 stage의 레시피가 다시 돈다. 첫 항목(직전 Boot stage)만 빼면
 * UpgradeSpringFramework_7_0 → 6_2 → 6_1 처럼 라이브러리 체인 안에 든 지난 stage 레시피가 남으므로, 직전 stage에서
 * 돈 레시피(전이 폐포)와 겹치는 선언형 레시피는 펼쳐서 이미 돈 것을 뺀다. 옵션이 있는 레시피는 이름이 같아도 옵션이 달라 빼지 않는다. 생성 파일이라
 * 직접 고치지 않고 ./gradlew syncUpstreamStages 로 다시 만든다.
 */
public final class UpstreamStagesGenerator {

	public static final Path OUTPUT = Path.of("src/main/resources/META-INF/rewrite/upstream/boot-stages.yml");
	static final String STAGE_PREFIX = "com.eottabom.rewrite.upstream.Boot_";

	/**
	 * 펼친 라이브러리 체인의 이름. 예)
	 * com.eottabom.rewrite.upstream.boot4_0.UpgradeSpringFramework_7_0
	 */
	static final String EXPANDED_PREFIX = "com.eottabom.rewrite.upstream.boot";

	private static final String FIRST_PREVIOUS = "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0";

	/** stage → upstream 레시피. 3.0 은 2.x 에서 올라오는 입구라 체인을 그대로 쓰므로 만들지 않는다. */
	private static final Map<String, String> UPSTREAM = new LinkedHashMap<>();

	static {
		UPSTREAM.put("3.1", "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_1");
		UPSTREAM.put("3.2", "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_2");
		UPSTREAM.put("3.3", "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3");
		UPSTREAM.put("3.4", "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_4");
		UPSTREAM.put("3.5", "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5");
		UPSTREAM.put("4.0", "org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0");
	}

	private UpstreamStagesGenerator() {
	}

	public static void main(String[] args) throws IOException {
		Files.writeString(OUTPUT, generate());
		System.out.println("생성: " + OUTPUT);
		VersionCatalogRulesGenerator.main(args);
	}

	static @Nullable String upstreamOf(String version) {
		return UPSTREAM.get(version);
	}

	static String previousOf(String version) {
		String previous = FIRST_PREVIOUS;
		for (Map.Entry<String, String> e : UPSTREAM.entrySet()) {
			if (e.getKey().equals(version)) {
				return previous;
			}
			previous = e.getValue();
		}
		throw new IllegalArgumentException(version);
	}

	@SuppressWarnings("unchecked")
	static String generate() {
		Map<String, Map<String, Object>> upstream = upstreamRecipes();
		List<Object> docs = new ArrayList<>();
		String previous = FIRST_PREVIOUS;
		for (Map.Entry<String, String> e : UPSTREAM.entrySet()) {
			Map<String, Object> source = upstream.get(e.getValue());
			if (source == null) {
				throw new IllegalStateException("rewrite-spring 에 " + e.getValue() + " 이 없다");
			}
			List<Object> recipeList = new ArrayList<>((List<Object>) source.get("recipeList"));
			if (recipeList.isEmpty() || !previous.equals(recipeList.get(0))) {
				throw new IllegalStateException(
						e.getValue() + " 의 첫 항목이 직전 stage(" + previous + ")가 아니다. upstream 구조가 바뀌었는지 확인한다");
			}
			recipeList.remove(0);
			String stage = e.getKey().replace('.', '_');
			Set<String> ran = closure(previous, upstream);
			List<Map<String, Object>> expanded = new ArrayList<>();
			Map<String, Object> stageRecipe = new LinkedHashMap<>();
			stageRecipe.put("type", "specs.openrewrite.org/v1beta/recipe");
			stageRecipe.put("name", STAGE_PREFIX + stage);
			stageRecipe.put("displayName", "upstream Spring Boot " + e.getKey() + " stage (직전 stage 체인 제외)");
			if (source.containsKey("preconditions")) {
				stageRecipe.put("preconditions", source.get("preconditions"));
			}
			List<String> removed = new ArrayList<>();
			stageRecipe.put("recipeList", withoutRan(recipeList, ran, upstream, stage, expanded, removed));
			stageRecipe.put("description",
					e.getValue() + " 에서 첫 항목인 직전 stage(" + previous + ")를 뺀 것." + removedNote(removed));
			moveAfterDisplayName(stageRecipe);
			docs.add(stageRecipe);
			docs.addAll(expanded);
			previous = e.getValue();
		}
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setAllowUnicode(true);
		options.setWidth(160);
		return RecipeFiles.SCHEMA_COMMENT + "\n"
				+ "# 생성 파일: ./gradlew syncUpstreamStages (UpstreamStagesGenerator). 직접 고치지 않는다.\n" + "# 출처: "
				+ springJar().getFileName() + "\n" + "---\n" + new Yaml(options).dumpAll(docs.iterator());
	}

	/**
	 * 이미 돈 레시피를 뺀 목록. 옵션 없는 선언형 레시피가 이미 돈 레시피를 품고 있으면 펼친 사본(expanded)으로 바꾼다.
	 */
	@SuppressWarnings("unchecked")
	private static List<Object> withoutRan(List<Object> recipeList, Set<String> ran,
			Map<String, Map<String, Object>> upstream, String stage, List<Map<String, Object>> expanded,
			List<String> removed) {
		List<Object> kept = new ArrayList<>();
		for (Object item : recipeList) {
			if (!(item instanceof String name)) {
				kept.add(item);
				continue;
			}
			if (ran.contains(name)) {
				removed.add(name);
				continue;
			}
			Map<String, Object> declarative = upstream.get(name);
			Set<String> inside = closure(name, upstream);
			inside.remove(name);
			if (declarative == null || inside.stream().noneMatch(ran::contains)) {
				kept.add(item);
				continue;
			}
			String copy = EXPANDED_PREFIX + stage + "." + name.substring(name.lastIndexOf('.') + 1);
			if (expanded.stream().noneMatch((doc) -> copy.equals(doc.get("name")))) {
				Map<String, Object> doc = new LinkedHashMap<>();
				doc.put("type", "specs.openrewrite.org/v1beta/recipe");
				doc.put("name", copy);
				doc.put("displayName",
						name.substring(name.lastIndexOf('.') + 1) + " (" + stage + " stage, 지난 stage 체인 제외)");
				doc.put("description", "");
				if (declarative.containsKey("preconditions")) {
					doc.put("preconditions", declarative.get("preconditions"));
				}
				// 자리를 먼저 잡아 두어 순환 체인에서도 한 번만 만든다
				expanded.add(doc);
				List<String> removedInside = new ArrayList<>();
				doc.put("recipeList", withoutRan(new ArrayList<>((List<Object>) declarative.get("recipeList")), ran,
						upstream, stage, expanded, removedInside));
				doc.put("description", name + " 에서 지난 stage 체인에 이미 들어 있던 레시피를 뺀 사본이다." + removedNote(removedInside));
			}
			kept.add(copy);
		}
		return kept;
	}

	/** 뺀 레시피 목록. 지난 stage에서 같은 레시피가 이미 돌았다 */
	private static String removedNote(List<String> removed) {
		return removed.isEmpty() ? "" : " 지난 stage에서 이미 돌아 뺀 레시피: " + String.join(", ", removed) + ".";
	}

	/** description 을 displayName 바로 뒤에 둔다 (recipeList 를 만든 뒤에야 뺀 목록을 알 수 있다) */
	private static void moveAfterDisplayName(Map<String, Object> stageRecipe) {
		Map<String, Object> ordered = new LinkedHashMap<>();
		for (String key : List.of("type", "name", "displayName", "description", "preconditions", "recipeList")) {
			if (stageRecipe.containsKey(key)) {
				ordered.put(key, stageRecipe.get(key));
			}
		}
		stageRecipe.clear();
		stageRecipe.putAll(ordered);
	}

	/** 레시피가 실행하는 옵션 없는 레시피 이름 전부 (자기 자신 포함) */
	@SuppressWarnings("unchecked")
	static Set<String> closure(String name, Map<String, Map<String, Object>> upstream) {
		Set<String> found = new LinkedHashSet<>();
		Deque<String> pending = new ArrayDeque<>(List.of(name));
		while (!pending.isEmpty()) {
			String next = pending.pop();
			if (!found.add(next)) {
				continue;
			}
			Map<String, Object> declarative = upstream.get(next);
			if (declarative == null) {
				continue;
			}
			for (Object item : (List<Object>) declarative.getOrDefault("recipeList", List.of())) {
				if (item instanceof String child) {
					pending.push(child);
				}
			}
		}
		return found;
	}

	/** 테스트 classpath 의 모든 레시피 jar 에서 recipe 문서를 이름으로 모은다 (rewrite-spring 이 먼저). */
	static Map<String, Map<String, Object>> upstreamRecipes() {
		Map<String, Map<String, Object>> recipes = new LinkedHashMap<>();
		readRecipes(springJar(), recipes);
		for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
			if (entry.endsWith(".jar")) {
				readRecipes(Path.of(entry), recipes);
			}
		}
		return recipes;
	}

	@SuppressWarnings("unchecked")
	private static void readRecipes(Path jarFile, Map<String, Map<String, Object>> recipes) {
		try (JarFile jar = new JarFile(jarFile.toFile())) {
			Enumeration<? extends ZipEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().startsWith("META-INF/rewrite/") || !entry.getName().endsWith(".yml")) {
					continue;
				}
				try (InputStream in = jar.getInputStream(entry)) {
					for (Object doc : new Yaml().loadAll(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
						if (doc instanceof Map<?, ?> map && map.get("name") != null
								&& String.valueOf(map.get("type")).endsWith("/recipe")) {
							recipes.putIfAbsent(String.valueOf(map.get("name")), (Map<String, Object>) map);
						}
					}
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Path springJar() {
		try {
			return Path.of(AddRouteTrailingSlash.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		}
		catch (Exception ex) {
			throw new IllegalStateException("rewrite-spring jar 를 찾지 못했다", ex);
		}
	}

}
