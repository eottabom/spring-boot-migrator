package com.eottabom.rewrite.custom.querydsl;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;

/**
 * querydsl-apt(jakarta) 앞에 jakarta.annotation-api, jakarta.persistence-api
 * annotationProcessor 가 없으면 넣는다. upstream 이 javax 기준으로 지우는 경우가 있고, AddDependency 는 갱신되지
 * 않은 Gradle 모델을 봐서 복구하지 못한다.
 */
public class EnsureQuerydslAptJakartaApis extends Recipe {

	private static final String ANNOTATION_PROCESSOR = "annotationProcessor";

	private static final String[] REQUIRED = { "jakarta.annotation:jakarta.annotation-api",
			"jakarta.persistence:jakarta.persistence-api" };

	@Override
	public String getDisplayName() {
		return "QueryDSL APT 에 필요한 Jakarta API 의존성 보장";
	}

	@Override
	public String getDescription() {
		return "Q-class 생성에 querydsl-apt(jakarta) 가 필요로 하는 jakarta.annotation-api / jakarta.persistence-api 를 annotationProcessor 에 추가한다. 이미 있으면 아무것도 하지 않는다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		// Groovy 와 Kotlin 빌드 스크립트를 모두 받으려고 JavaIsoVisitor 를 쓴다 (GroovyIsoVisitor 는 kts 를
		// 건너뛴다)
		return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<>() {
			@Override
			public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
				J.Block visited = super.visitBlock(block, ctx);

				List<Statement> statements = visited.getStatements();
				int aptIndex = -1;
				J.MethodInvocation apt = null;
				List<String> present = new ArrayList<>();
				for (int i = 0; i < statements.size(); i++) {
					J.MethodInvocation m = asMethodInvocation(statements.get(i));
					if (m == null || !ANNOTATION_PROCESSOR.equals(m.getSimpleName())) {
						continue;
					}
					String notation = dependencyNotation(m);
					if (notation == null) {
						continue;
					}
					if (apt == null && notation.startsWith("com.querydsl:querydsl-apt:")
							&& notation.endsWith(":jakarta")) {
						apt = m;
						aptIndex = i;
					}
					for (String required : REQUIRED) {
						if (notation.startsWith(required)) {
							present.add(required);
						}
					}
				}
				if (apt == null) {
					return visited;
				}

				List<Statement> toAdd = new ArrayList<>();
				for (String required : REQUIRED) {
					if (!present.contains(required)) {
						toAdd.add(copyWithNotation(apt, required));
					}
				}
				if (toAdd.isEmpty()) {
					return visited;
				}

				// apt 선언의 prefix(앞 줄 주석 포함)는 첫 줄이 가져가고, 나머지는 줄바꿈과 들여쓰기만 갖는다
				Space aptPrefix = statements.get(aptIndex).getPrefix();
				Space lineBreak = Space.format("\n" + indentOf(aptPrefix));
				List<Statement> updated = new ArrayList<>(statements.subList(0, aptIndex));
				for (int i = 0; i < toAdd.size(); i++) {
					updated.add(toAdd.get(i).withPrefix((i == 0) ? aptPrefix : lineBreak));
				}
				updated.add(statements.get(aptIndex).withPrefix(lineBreak));
				updated.addAll(statements.subList(aptIndex + 1, statements.size()));
				return visited.withStatements(updated);
			}
		});
	}

	private static J.@Nullable MethodInvocation asMethodInvocation(Statement statement) {
		if (statement instanceof J.MethodInvocation invocation) {
			return invocation;
		}
		// Groovy closure 의 마지막 문장은 암묵적 return 으로 감싸진다
		if (statement instanceof J.Return returned
				&& returned.getExpression() instanceof J.MethodInvocation invocation) {
			return invocation;
		}
		return null;
	}

	private static @Nullable String dependencyNotation(J.MethodInvocation dependency) {
		if (dependency.getArguments().isEmpty()) {
			return null;
		}
		Expression first = dependency.getArguments().get(0);
		if (first instanceof J.Literal literal && literal.getValue() instanceof String value) {
			return value;
		}
		if (first instanceof G.GString gString) {
			// ${version} 은 버전 자리라 상수 조각만 본다
			StringBuilder notation = new StringBuilder();
			for (J part : gString.getStrings()) {
				notation.append((part instanceof J.Literal literal) ? String.valueOf(literal.getValue()) : "${}");
			}
			return notation.toString();
		}
		return null;
	}

	private static Statement copyWithNotation(J.MethodInvocation apt, String notation) {
		Expression first = apt.getArguments().get(0);
		String quote = "\"";
		if (first instanceof J.Literal original && original.getValueSource() != null
				&& original.getValueSource().startsWith("'")) {
			quote = "'";
		}
		J.Literal literal = new J.Literal(Tree.randomId(), first.getPrefix(), first.getMarkers(), notation,
				quote + notation + quote, null, JavaType.Primitive.String);
		List<Expression> args = new ArrayList<>();
		args.add(literal);
		return apt.withId(Tree.randomId()).withArguments(args);
	}

	private static String indentOf(Space prefix) {
		String ws = prefix.getWhitespace();
		int nl = ws.lastIndexOf('\n');
		return (nl >= 0) ? ws.substring(nl + 1) : ws;
	}

}
