package com.eottabom.rewrite.custom.gradle;

import java.util.List;

import com.eottabom.rewrite.support.GradleDsl;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.semver.LatestRelease;

/**
 * {@code jacoco { toolVersion }} 을 지정 버전 이상으로 올린다. 새 Java 클래스 파일을 읽으려면 그에 맞는 JaCoCo 가
 * 필요한데 upstream UpgradeJaCoCo 는 org.jacoco 의존성만 올린다.
 */
public class UpgradeJacocoToolVersion extends Recipe {

	@Option(displayName = "Version", example = "0.8.15")
	private final String version;

	public UpgradeJacocoToolVersion(String version) {
		this.version = version;
	}

	public String getVersion() {
		return this.version;
	}

	@Override
	public String getDisplayName() {
		return "JaCoCo 도구 버전 업그레이드";
	}

	@Override
	public String getDescription() {
		return "JaCoCo 설정의 toolVersion 을 지정 버전 이상으로 올린다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		// Groovy 와 Kotlin 빌드 스크립트를 모두 받으려고 JavaIsoVisitor 를 쓴다
		return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<>() {
			@Override
			public J.Assignment visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
				J.Assignment visited = super.visitAssignment(assignment, ctx);
				J.Literal literal = toolVersionLiteral(visited);
				return (literal == null || !insideJacocoBlock()) ? visited : visited.withAssignment(upgraded(literal));
			}

			@Override
			public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, ExecutionContext ctx) {
				J.MethodInvocation visited = super.visitMethodInvocation(invocation, ctx);
				if (!"toolVersion".equals(visited.getSimpleName()) || !insideJacocoBlock()
						|| visited.getArguments().size() != 1
						|| !(visited.getArguments().get(0) instanceof J.Literal literal)
						|| !(literal.getValue() instanceof String)) {
					return visited;
				}
				return visited.withArguments(List.of(upgraded(literal)));
			}

			private J.Literal upgraded(J.Literal literal) {
				if (!(literal.getValue() instanceof String current) || !isOlder(current)) {
					return literal;
				}
				return GradleDsl.withStringValue(literal, UpgradeJacocoToolVersion.this.version);
			}

			private static J.@Nullable Literal toolVersionLiteral(J.Assignment assignment) {
				boolean toolVersion = assignment.getVariable() instanceof J.Identifier variable
						&& "toolVersion".equals(variable.getSimpleName());
				return (toolVersion && assignment.getAssignment() instanceof J.Literal literal
						&& literal.getValue() instanceof String) ? literal : null;
			}

			private boolean insideJacocoBlock() {
				J.MethodInvocation enclosing = getCursor().getParentOrThrow().firstEnclosing(J.MethodInvocation.class);
				return enclosing != null && "jacoco".equals(enclosing.getSimpleName());
			}
		});
	}

	private boolean isOlder(String current) {
		return new LatestRelease(null).compare(null, current, this.version) < 0;
	}

}
