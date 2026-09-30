package com.eottabom.rewrite.custom.querydsl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.eottabom.rewrite.support.GradleDsl;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

/**
 * querydsl-jpa, querydsl-apt 에 jakarta classifier 를 붙인다. upstream
 * ChangeDependencyClassifier 는 버전이 있는 문자열만 다뤄서 GString 버전 변수와 BOM 관리 선언을 바꾸지 못한다. jpa 이외의
 * classifier 는 그대로 둔다.
 */
public class UseQuerydslJakartaClassifier extends Recipe {

	private static final String JAKARTA = "jakarta";

	private static final Pattern GSTRING_PREFIX = Pattern.compile("^com\\.querydsl:querydsl-(jpa|apt):$");

	@Override
	public String getDisplayName() {
		return "QueryDSL Jakarta Classifier 적용";
	}

	@Override
	public String getDescription() {
		return "QueryDSL 의 querydsl-jpa / querydsl-apt 의존성에 `jakarta` classifier 를 적용한다. GString 버전 변수와 BOM 관리(버전 생략) 선언도 처리한다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		// Groovy 와 Kotlin 빌드 스크립트를 모두 받으려고 JavaIsoVisitor 를 쓴다 (GroovyIsoVisitor 는 kts 를
		// 건너뛴다)
		return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<>() {
			@Override
			public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
				J.MethodInvocation visited = super.visitMethodInvocation(method, ctx);
				List<Expression> args = visited.getArguments();
				if (args.isEmpty()) {
					return visited;
				}

				Expression first = args.get(0);
				if (first instanceof J.Literal literal && literal.getValue() instanceof String gav) {
					String updated = withJakartaClassifier(gav);
					if (updated != null) {
						return visited.withArguments(
								GradleDsl.withFirstArgument(args, GradleDsl.withStringValue(literal, updated)));
					}
				}
				else if (first instanceof G.GString gString) {
					G.GString updated = withJakartaClassifier(gString);
					if (updated != first) {
						return visited.withArguments(GradleDsl.withFirstArgument(args, updated));
					}
				}
				return visited;
			}
		});
	}

	/** 변경이 필요 없으면 null */
	static @Nullable String withJakartaClassifier(String gav) {
		String[] parts = gav.split(":", -1);
		if (parts.length < 2 || !"com.querydsl".equals(parts[0]) || !isTarget(parts[1])) {
			return null;
		}
		String version = (parts.length >= 3) ? parts[2] : "";
		if (parts.length >= 4) {
			String classifier = parts[3];
			// jakarta 이거나 jpa 이외의 classifier 는 의도된 것으로 본다
			if (JAKARTA.equals(classifier) || !(classifier.isEmpty() || "jpa".equals(classifier))) {
				return null;
			}
		}
		return parts[0] + ":" + parts[1] + ":" + version + ":" + JAKARTA;
	}

	private static G.GString withJakartaClassifier(G.GString gString) {
		List<J> strings = gString.getStrings();
		if (strings.size() < 2 || !(strings.get(0) instanceof J.Literal head)
				|| !(head.getValue() instanceof String prefix) || !GSTRING_PREFIX.matcher(prefix).matches()) {
			return gString;
		}

		J last = strings.get(strings.size() - 1);
		if (last instanceof G.GString.Value) {
			return gString.withStrings(ListUtils.concat(strings, newFragment(":" + JAKARTA)));
		}
		if (last instanceof J.Literal tail && ":jpa".equals(tail.getValue())) {
			List<J> replaced = new ArrayList<>(strings);
			replaced.set(replaced.size() - 1, newFragment(":" + JAKARTA));
			return gString.withStrings(replaced);
		}
		return gString;
	}

	private static boolean isTarget(String artifactId) {
		return "querydsl-jpa".equals(artifactId) || "querydsl-apt".equals(artifactId);
	}

	private static J.Literal newFragment(String value) {
		return new J.Literal(Tree.randomId(), Space.EMPTY, Markers.EMPTY, value, value, null,
				JavaType.Primitive.String);
	}

}
