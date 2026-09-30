package com.eottabom.rewrite.custom.gradle;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

/**
 * 레시피가 빌드 스크립트에 문자열로 추가한 의존성을 version catalog 항목으로 옮긴다. 사용자가 원래 문자열로 쓴 의존성은 두고, catalog 를
 * 이미 쓰는 빌드 스크립트에만 적용한다. 다른 레시피가 추가한 뒤에 돌아야 해서 stage 마지막에 둔다.
 */
public class DeclareAddedDependenciesInVersionCatalog extends Recipe {

	/** 빌드 스크립트에서 정한 alias 와 좌표. catalog 는 다음 사이클에 이것을 읽어 항목을 더한다 */
	static final String ADDED = DeclareAddedDependenciesInVersionCatalog.class.getName() + ".added";

	private static final Pattern COORDINATES = Pattern.compile("[\\w.\\-]+:[\\w.\\-]+(:[\\w.+\\-]+)?");

	private static final Pattern QUOTED_COORDINATES = Pattern.compile("[\"'](" + COORDINATES.pattern() + ")[\"']");

	private static final String DEFAULT_CATALOG = "libs";

	@Override
	public String getDisplayName() {
		return "새로 추가된 의존성을 Version Catalog 로 선언";
	}

	@Override
	public String getDescription() {
		return "레시피가 문자열로 추가한 의존성을 version catalog 항목과 접근자로 바꾼다. 원래 있던 문자열 선언은 그대로 둔다.";
	}

	@Override
	public List<Recipe> getRecipeList() {
		return List.of(new UseCatalogAccessors(), new AddCatalogEntries());
	}

	static String accessor(String catalog, String alias) {
		return catalog + "." + alias.replace('-', '.').replace('_', '.');
	}

	static boolean sameAccessor(String left, String right) {
		return left.replaceAll("[-_.]", ".").equalsIgnoreCase(right.replaceAll("[-_.]", "."));
	}

	@SuppressWarnings("unchecked")
	static Map<String, Added> added(ExecutionContext ctx) {
		return ctx.computeMessageIfAbsent(ADDED, (key) -> new LinkedHashMap<>());
	}

	/**
	 * @param catalog 항목을 더할 catalog 이름
	 * @param cycle 빌드 스크립트를 바꾼 사이클
	 */
	record Added(String catalog, String coordinates, int cycle) {
	}

	static final class Accumulator {

		private final Map<String, String> catalogs = new TreeMap<>();

		private final Map<String, String> aliases = new HashMap<>();

		/** 원본 빌드 스크립트에 사용자가 쓴 모듈(group:artifact). 다른 레시피가 버전만 바꾸거나 지운 선언도 원래 선언이다 */
		private final Map<Path, Set<String>> original = new HashMap<>();

		private final Map<Path, String> scripts = new HashMap<>();

		void catalog(String name, String toml) {
			this.catalogs.put(name, toml);
		}

		/** 새 항목을 더할 catalog. libs 가 있으면 libs, 없으면 catalog 가 하나일 때만 그것 */
		@Nullable String target() {
			if (this.catalogs.containsKey(DEFAULT_CATALOG)) {
				return DEFAULT_CATALOG;
			}
			return (this.catalogs.size() == 1) ? this.catalogs.keySet().iterator().next() : null;
		}

		void buildScript(Path path, String text) {
			this.scripts.put(path, text);
			Set<String> coordinates = new HashSet<>();
			Matcher quoted = QUOTED_COORDINATES.matcher(text);
			while (quoted.find()) {
				coordinates.add(module(quoted.group(1)));
			}
			this.original.put(path, coordinates);
		}

		/** 대상 catalog 가 있고 이 빌드 스크립트가 이미 그 접근자를 쓴다 */
		boolean appliesTo(Path path) {
			String target = target();
			return target != null && this.scripts.getOrDefault(path, "").contains(target + ".");
		}

		/** 원본 빌드 스크립트에 사용자가 직접 쓴 모듈 (버전은 보지 않는다) */
		boolean isOriginal(Path path, String coordinates) {
			return this.original.getOrDefault(path, Set.of()).contains(module(coordinates));
		}

		private static String module(String coordinates) {
			String[] gav = coordinates.split(":");
			return gav[0] + ":" + gav[1];
		}

		/** 모듈이 catalog 에 있으면 그 alias, 없으면 artifact 이름 (겹치면 group 마지막 이름을 붙인다) */
		String aliasFor(String group, String artifact) {
			if (this.aliases.isEmpty()) {
				String toml = Objects.requireNonNull(this.catalogs.get(Objects.requireNonNull(target())));
				VersionCatalogEditor.libraryAliases(toml).forEach(this.aliases::putIfAbsent);
			}
			String module = group + ":" + artifact;
			String existing = this.aliases.get(module);
			if (existing != null) {
				return existing;
			}
			// Gradle 은 -, _, . 를 같은 접근자로 만든다 (spring-boot 와 spring_boot 는 겹친다)
			boolean taken = this.aliases.values().stream().anyMatch((alias) -> sameAccessor(alias, artifact));
			String alias = taken ? group.substring(group.lastIndexOf('.') + 1) + "-" + artifact : artifact;
			this.aliases.put(module, alias);
			return alias;
		}

	}

	static class UseCatalogAccessors extends ScanningRecipe<Accumulator> {

		@Override
		public String getDisplayName() {
			return "새로 추가된 의존성 문자열을 Version Catalog 접근자로";
		}

		@Override
		public String getDescription() {
			return "원본 빌드 스크립트에 없던 의존성 좌표 문자열을 version catalog 접근자로 바꾼다.";
		}

		@Override
		public boolean causesAnotherCycle() {
			return true;
		}

		@Override
		public Accumulator getInitialValue(ExecutionContext ctx) {
			return new Accumulator();
		}

		@Override
		public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
			return new TreeVisitor<>() {
				@Override
				public Tree preVisit(Tree tree, ExecutionContext ctx) {
					stopAfterPreVisit();
					SourceFile source = (SourceFile) tree;
					String catalog = VersionCatalogSource.catalogName(source);
					if (catalog != null) {
						acc.catalog(catalog, source.printAll());
					}
					else if (isBuildScript(source.getSourcePath())) {
						acc.buildScript(source.getSourcePath(), source.printAll());
					}
					return tree;
				}
			};
		}

		@Override
		public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
			return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<>() {
				@Override
				public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
					J.MethodInvocation visited = super.visitMethodInvocation(method, ctx);
					Path path = getCursor().firstEnclosingOrThrow(SourceFile.class).getSourcePath();
					J.Literal literal = coordinateLiteral(visited);
					if (literal == null || !acc.appliesTo(path) || !inDependenciesBlock(getCursor())) {
						return visited;
					}
					String coordinates = (String) Objects.requireNonNull(literal.getValue());
					if (acc.isOriginal(path, coordinates)) {
						return visited;
					}
					String[] gav = coordinates.split(":");
					String catalog = Objects.requireNonNull(acc.target());
					String alias = acc.aliasFor(gav[0], gav[1]);
					added(ctx).putIfAbsent(alias, new Added(catalog, coordinates, ctx.getCycle()));
					J.Identifier catalogAccessor = new J.Identifier(Tree.randomId(), literal.getPrefix(),
							literal.getMarkers(), List.of(), accessor(catalog, alias), null, null);
					// Groovy 의 괄호 없는 호출 표시(OmitParentheses)가 인자 마커에 있어 원래 마커를 그대로 쓴다
					return visited.withArguments(List.of(catalogAccessor));
				}
			});
		}

		private static J.@Nullable Literal coordinateLiteral(J.MethodInvocation invocation) {
			if (invocation.getArguments().size() != 1
					|| !(invocation.getArguments().get(0) instanceof J.Literal literal)) {
				return null;
			}
			return (literal.getValue() instanceof String value && COORDINATES.matcher(value).matches()) ? literal
					: null;
		}

		private static boolean isBuildScript(Path path) {
			String name = path.getFileName().toString();
			return name.equals("build.gradle") || name.equals("build.gradle.kts");
		}

		private static boolean inDependenciesBlock(Cursor cursor) {
			boolean dependencies = false;
			for (Cursor parent = cursor.getParent(); parent != null; parent = parent.getParent()) {
				if (parent.getValue() instanceof J.MethodInvocation invocation) {
					String name = invocation.getSimpleName();
					if (name.equals("buildscript") || name.equals("constraints")) {
						return false;
					}
					dependencies |= name.equals("dependencies");
				}
			}
			return dependencies;
		}

	}

	static class AddCatalogEntries extends Recipe {

		@Override
		public String getDisplayName() {
			return "새로 추가된 의존성을 Version Catalog 에 선언";
		}

		@Override
		public String getDescription() {
			return "빌드 스크립트에서 접근자로 바꾼 의존성을 version catalog 의 [libraries] 에 더한다.";
		}

		@Override
		public TreeVisitor<?, ExecutionContext> getVisitor() {
			return new TreeVisitor<>() {
				@Override
				public Tree preVisit(Tree tree, ExecutionContext ctx) {
					stopAfterPreVisit();
					SourceFile source = (SourceFile) tree;
					String catalog = VersionCatalogSource.catalogName(source);
					Map<String, String> libraries = new LinkedHashMap<>();
					added(ctx).forEach((alias, added) -> {
						if (added.catalog().equals(catalog) && added.cycle() < ctx.getCycle()) {
							libraries.put(alias, added.coordinates());
						}
					});
					return libraries.isEmpty() ? tree : VersionCatalogSource.withText(source,
							VersionCatalogEditor.addLibraries(source.printAll(), libraries), ctx);
				}
			};
		}

	}

}
