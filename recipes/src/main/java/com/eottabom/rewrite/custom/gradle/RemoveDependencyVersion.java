package com.eottabom.rewrite.custom.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

/**
 * "group:artifact:version" 선언에서 버전만 지워 BOM 에 맡긴다. upstream
 * RemoveRedundantDependencyVersions 는 transitive 로 충족되는 의존성을 통째로 지우는 로직이 함께 돌아서 쓸 수 없다.
 */
public class RemoveDependencyVersion extends Recipe {

	@Option(displayName = "Group", example = "org.springframework.restdocs")
	private final String groupId;

	public RemoveDependencyVersion(String groupId) {
		this.groupId = groupId;
	}

	public String getGroupId() {
		return this.groupId;
	}

	private static boolean isBom(String artifactId) {
		return "bom".equals(artifactId) || artifactId.endsWith("-bom") || artifactId.endsWith("-dependencies")
				|| artifactId.endsWith("-platform");
	}

	/** 버전이 있어야 하는 호출: BOM import, 강제 버전 */
	private static final Set<String> KEEPS_VERSION = Set.of("classpath", "platform", "enforcedPlatform", "force");

	@Override
	public String getDisplayName() {
		return "의존성 버전 명시 제거";
	}

	@Override
	public String getDescription() {
		return "지정한 group 의 \"group:artifact:version\" 선언에서 버전을 지워 BOM 관리 버전을 따르게 한다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		// Groovy 와 Kotlin 빌드 스크립트를 모두 받으려고 JavaIsoVisitor 를 쓴다
		return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<>() {
			@Override
			public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
				J.MethodInvocation visited = super.visitMethodInvocation(method, ctx);
				// buildscript classpath(플러그인)는 BOM 이 관리하지 않고, platform / force /
				// constraints 는 버전이 있어야 한다
				J.Literal literal = firstStringArgument(visited);
				if (KEEPS_VERSION.contains(visited.getSimpleName()) || literal == null
						|| !inDependenciesBlock(getCursor())) {
					return visited;
				}
				String[] parts = ((String) Objects.requireNonNull(literal.getValue())).split(":", -1);
				if (!isVersionedInGroup(parts)) {
					return visited;
				}
				String value = parts[0] + ":" + parts[1];
				String source = literal.getValueSource();
				String quote = (source != null && !source.isEmpty()) ? source.substring(0, 1) : "'";
				J.Literal updated = literal.withValue(value).withValueSource(quote + value + quote);
				return visited.withArguments(withFirst(visited.getArguments(), updated));
			}

			/** 대상 그룹의 group:artifact:version. classifier 가 있는 선언과 BOM 좌표는 건드리지 않는다 */
			private boolean isVersionedInGroup(String[] parts) {
				return parts.length == 3 && RemoveDependencyVersion.this.groupId.equals(parts[0]) && !parts[2].isEmpty()
						&& !isBom(parts[1]);
			}
		});
	}

	/** dependencies { } 안의 선언. buildscript, constraints, resolutionStrategy 안은 뺀다 */
	private static boolean inDependenciesBlock(Cursor cursor) {
		boolean dependencies = false;
		for (Cursor parent = cursor.getParent(); parent != null; parent = parent.getParent()) {
			if (parent.getValue() instanceof J.MethodInvocation invocation) {
				String name = invocation.getSimpleName();
				if (name.equals("buildscript") || name.equals("constraints") || name.equals("resolutionStrategy")
						|| name.equals("configurations")) {
					return false;
				}
				dependencies |= name.equals("dependencies");
			}
		}
		return dependencies;
	}

	private static List<Expression> withFirst(List<Expression> arguments, Expression first) {
		List<Expression> replaced = new ArrayList<>(arguments);
		replaced.set(0, first);
		return replaced;
	}

	private static J.@Nullable Literal firstStringArgument(J.MethodInvocation invocation) {
		return (!invocation.getArguments().isEmpty() && invocation.getArguments().get(0) instanceof J.Literal literal
				&& literal.getValue() instanceof String) ? literal : null;
	}

}
