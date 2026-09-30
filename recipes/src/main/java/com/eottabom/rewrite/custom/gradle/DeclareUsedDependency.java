package com.eottabom.rewrite.custom.gradle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.AddDependencyVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;

/**
 * 코드가 import 하지만 transitive 로만 들어오던 의존성을 직접 선언한다. 버전을 올리며 의존성 그래프가 바뀌면 사라져 컴파일이 깨진다.
 * upstream AddDependency 는 업그레이드 전 그래프 기준으로 이미 있다고 보고, onlyIfUsing 은 타입 해석에 기대서 쓸 수 없다.
 */
public class DeclareUsedDependency extends ScanningRecipe<DeclareUsedDependency.Accumulator> {

	@Option(displayName = "Packages",
			description = "이 패키지(하위 포함)를 import 하면 대상으로 본다. "
					+ "같은 실행 안에서 upstream 레시피가 패키지를 바꾸는 경우(ex. commons-lang -> lang3) 바뀌기 전 패키지도 함께 적는다. "
					+ "(스캔은 편집 전 원본 소스 기준으로 한 번만 돈다)",
			example = "org.apache.commons.lang3")
	private final List<String> packageNames;

	@Option(displayName = "Group", example = "org.apache.commons")
	private final String groupId;

	@Option(displayName = "Artifact", example = "commons-lang3")
	private final String artifactId;

	@Option(displayName = "Version", description = "BOM 이 관리하면 생략한다. semver selector 사용 가능 (ex. 2.x)", example = "2.x",
			required = false)
	private final @Nullable String version;

	@Option(displayName = "Version pattern", description = "버전 뒤에 붙는 접미사 패턴 (ex. guava 의 -jre)", example = "-jre",
			required = false)
	private final @Nullable String versionPattern;

	public DeclareUsedDependency(List<String> packageNames, String groupId, String artifactId, @Nullable String version,
			@Nullable String versionPattern) {
		this.packageNames = packageNames;
		this.groupId = groupId;
		this.artifactId = artifactId;
		this.version = version;
		this.versionPattern = versionPattern;
	}

	@Override
	public String getDisplayName() {
		return "사용 중인데 선언되지 않은 의존성 선언";
	}

	@Override
	public String getDescription() {
		return "소스가 import 하는 패키지의 의존성이 build.gradle 에 직접 선언돼 있지 않으면 implementation(테스트 전용이면 testImplementation)으로 추가한다.";
	}

	@Override
	public Accumulator getInitialValue(ExecutionContext ctx) {
		return new Accumulator(new HashMap<>());
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
		List<String> prefixes = this.packageNames.stream().map((name) -> name + ".").toList();
		return new TreeVisitor<>() {
			@Override
			public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
				if (!(tree instanceof J.CompilationUnit cu)) {
					return tree;
				}
				cu.getMarkers()
					.findFirst(JavaProject.class)
					.ifPresent((project) -> cu.getMarkers()
						.findFirst(JavaSourceSet.class)
						.filter((sourceSet) -> usesPackage(cu, prefixes))
						.ifPresent((sourceSet) -> acc.sourceSetsUsing()
							.computeIfAbsent(project, (key) -> new HashSet<>())
							.add(sourceSet.getName())));
				return tree;
			}
		};
	}

	/**
	 * import(정적 import, * 포함) 나 본문의 패키지 전체 이름으로 패키지를 쓴다. 의존성이 빠지면 타입이 해석되지 않으므로 이름으로 본다
	 */
	private static boolean usesPackage(J.CompilationUnit cu, List<String> prefixes) {
		if (cu.getImports().stream().anyMatch((imp) -> inPackage(qualifiedName(imp.getQualid()), prefixes))) {
			return true;
		}
		var found = new AtomicBoolean();
		new JavaIsoVisitor<AtomicBoolean>() {
			@Override
			public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, AtomicBoolean uses) {
				if (uses.get()) {
					return fieldAccess;
				}
				if (inPackage(qualifiedName(fieldAccess), prefixes)) {
					uses.set(true);
					return fieldAccess;
				}
				return super.visitFieldAccess(fieldAccess, uses);
			}

			@Override
			public J.Import visitImport(J.Import imp, AtomicBoolean uses) {
				return imp;
			}
		}.visit(cu, found);
		return found.get();
	}

	/** lang 이 lang3 에 매칭되지 않도록 '.' 까지 비교한다 */
	private static boolean inPackage(String qualifiedName, List<String> prefixes) {
		String name = qualifiedName + ".";
		return prefixes.stream().anyMatch(name::startsWith);
	}

	/** a.b.C 처럼 이름으로만 이어진 식의 전체 이름. 메서드 호출 등이 섞이면 빈 문자열 */
	private static String qualifiedName(Expression expression) {
		if (expression instanceof J.Identifier identifier) {
			return identifier.getSimpleName();
		}
		if (expression instanceof J.FieldAccess fieldAccess) {
			String target = qualifiedName(fieldAccess.getTarget());
			return target.isEmpty() ? "" : target + "." + fieldAccess.getSimpleName();
		}
		return "";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
		return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<>() {
			@Override
			public @Nullable J visit(@Nullable Tree tree, ExecutionContext ctx) {
				// IsBuildGradle 뒤라 빌드 스크립트(Groovy, Kotlin 컴파일 단위)만 온다
				JavaSourceFile buildScript = (JavaSourceFile) Objects.requireNonNull(tree);
				Set<String> using = buildScript.getMarkers()
					.findFirst(JavaProject.class)
					.map(acc.sourceSetsUsing()::get)
					.orElse(Set.of());
				return buildScript.getMarkers()
					.findFirst(GradleProject.class)
					.map((gp) -> addDependency(buildScript, gp, using, ctx))
					.orElse(buildScript);
			}

			private J addDependency(JavaSourceFile buildScript, GradleProject gp, Set<String> using,
					ExecutionContext ctx) {
				// 소스셋마다 자기 configuration 에 넣는다 (main 에 넣으면 test 는 따라오고 testFixtures 는
				// 아니다)
				J result = buildScript;
				for (String sourceSet : using) {
					String configuration = "main".equals(sourceSet) ? "implementation" : sourceSet + "Implementation";
					if (gp.getConfiguration(configuration) == null
							|| isDeclared(gp, coveringConfigurations(sourceSet, using))) {
						continue;
					}
					result = new AddDependencyVisitor(DeclareUsedDependency.this.groupId,
							DeclareUsedDependency.this.artifactId, DeclareUsedDependency.this.version,
							DeclareUsedDependency.this.versionPattern, configuration, null, null, null,
							DeclareUsedDependency::isTopLevel, null)
						.visitNonNull(result, ctx);
				}
				return result;
			}
		});
	}

	private static @Nullable List<String> coveringConfigurations(String sourceSet, Set<String> using) {
		List<String> configurations = new ArrayList<>(Arrays.asList("api", "compileOnlyApi"));
		if ("main".equals(sourceSet)) {
			configurations.addAll(Arrays.asList("implementation", "compileOnly"));
		}
		else {
			configurations
				.addAll(Arrays.asList(sourceSet + "Implementation", sourceSet + "Api", sourceSet + "CompileOnly"));
			// test 는 main 의 implementation 을 상속한다
			if ("test".equals(sourceSet)) {
				configurations.addAll(Arrays.asList("implementation", "compileOnly"));
				if (using.contains("main")) {
					return null;
				}
			}
		}
		return configurations;
	}

	private boolean isDeclared(GradleProject gp, @Nullable List<String> configurations) {
		if (configurations == null) {
			return true;
		}
		for (String name : configurations) {
			GradleDependencyConfiguration configuration = gp.getConfiguration(name);
			if (configuration != null && configuration.findRequestedDependency(this.groupId, this.artifactId) != null) {
				return true;
			}
		}
		return false;
	}

	/** subprojects { } 안이 아니라 이 파일의 최상위 dependencies 블록에 넣는다 */
	private static boolean isTopLevel(Cursor cursor) {
		if (cursor.getValue() instanceof J.Block) {
			return cursor.getParentOrThrow().getValue() instanceof JavaSourceFile;
		}
		return cursor.getParentOrThrow().firstEnclosing(J.MethodInvocation.class) == null;
	}

	public record Accumulator(Map<JavaProject, Set<String>> sourceSetsUsing) {
	}

}
