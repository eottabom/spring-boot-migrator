package com.eottabom.migration.result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eottabom.migration.io.TextFiles;

/**
 * rewriteRun 로그의 파일별 레시피 트리에서 말단 레시피를 가장 가까운 custom 레시피에 귀속시킨 변경 내역. custom 레시피에 귀속되지 않은
 * 파일은 upstream 레시피가 바꾼 것이다.
 *
 * @param byRecipe custom 레시피별로 바꾼 파일
 * @param changed 이 stage 에서 바뀐 모든 파일
 */
record RecipeChanges(Map<String, Set<String>> byRecipe, Set<String> changed) {

	private static final Pattern CHANGED_FILE = Pattern
		.compile("^(?:Changes have been made to|These recipes would make changes to) (.+?)(?: by)?:$");

	static RecipeChanges collect(Path rewriteLog, Set<String> projectRecipes) {
		RecipeChanges fixes = new RecipeChanges(new TreeMap<>(), new LinkedHashSet<>());
		Map<String, List<RecipeNode>> blocks = new LinkedHashMap<>();
		List<RecipeNode> current = null;
		for (String line : TextFiles.readLines(rewriteLog)) {
			Matcher changedFile = CHANGED_FILE.matcher(line);
			if (changedFile.find()) {
				fixes.changed().add(changedFile.group(1));
				current = new ArrayList<>();
				blocks.put(changedFile.group(1), current);
			}
			else if (current != null && line.startsWith("    ")) {
				String stripped = line.stripLeading();
				current.add(new RecipeNode(line.length() - stripped.length(),
						stripped.trim().replaceAll(":\\s*\\{.*$", "")));
			}
			else {
				current = null;
			}
		}
		blocks.forEach((file, nodes) -> {
			boolean buildFile = file.endsWith(".gradle") || file.endsWith(".gradle.kts") || file.endsWith(".properties")
					|| file.endsWith("pom.xml");
			List<RecipeNode> stack = new ArrayList<>();
			for (int i = 0; i < nodes.size(); i++) {
				RecipeNode node = nodes.get(i);
				while (!stack.isEmpty() && stack.get(stack.size() - 1).indent() >= node.indent()) {
					stack.remove(stack.size() - 1);
				}
				stack.add(node);
				boolean leaf = i == nodes.size() - 1 || nodes.get(i + 1).indent() <= node.indent();
				if (!leaf || (!buildFile && isDependencyRecipe(node.name()))) {
					continue;
				}
				for (int depth = stack.size() - 1; depth >= 0; depth--) {
					String ancestor = stack.get(depth).name();
					if (isCustomFix(ancestor, projectRecipes)) {
						fixes.byRecipe().computeIfAbsent(ancestor, (recipe) -> new TreeSet<>()).add(file);
						break;
					}
				}
			}
		});
		return fixes;
	}

	/** 의존성 레시피가 빌드 파일이 아닌 곳에 남긴 기록은 메타데이터 갱신이라 텍스트 변경이 없다 */
	private static boolean isDependencyRecipe(String name) {
		return name.startsWith("org.openrewrite.java.dependencies.") || name.startsWith("org.openrewrite.gradle.");
	}

	/** custom 레시피와 프로젝트 레시피만 보정으로 센다. 공통 보정 묶음은 세지 않는다 */
	static boolean isCustomFix(String name, Set<String> projectRecipes) {
		return projectRecipes.contains(name) || (name.startsWith("com.eottabom.rewrite.custom.")
				&& !name.equals("com.eottabom.rewrite.custom.CommonFixes"));
	}

	/** custom 레시피별로 바꾼 파일 */
	List<CustomChange> customChanges() {
		return this.byRecipe.entrySet()
			.stream()
			.map((entry) -> new CustomChange(entry.getKey(), List.copyOf(entry.getValue())))
			.toList();
	}

	/** custom 레시피가 바꾸지 않은 파일 */
	List<String> upstreamOnly() {
		Set<String> custom = new TreeSet<>();
		this.byRecipe.values().forEach(custom::addAll);
		return this.changed.stream().filter((file) -> !custom.contains(file)).toList();
	}

	public record CustomChange(String recipe, List<String> files) {
	}

	private record RecipeNode(int indent, String name) {
	}

}
