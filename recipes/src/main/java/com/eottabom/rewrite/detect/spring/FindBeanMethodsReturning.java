package com.eottabom.rewrite.detect.spring;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

/**
 * 반환 타입이 지정한 타입(하위 타입 포함)인 {@code @Bean} 메서드를 표시한다. upstream FindTypes 는 타입이 쓰인 모든 곳을
 * 표시한다.
 */
public class FindBeanMethodsReturning extends Recipe {

	private static final AnnotationMatcher BEAN = new AnnotationMatcher("@org.springframework.context.annotation.Bean");

	@Option(displayName = "Fully qualified type name", description = "이 타입이거나 이 타입을 상속/구현한 반환 타입",
			example = "org.springframework.http.converter.HttpMessageConverter")
	private final String fullyQualifiedTypeName;

	public FindBeanMethodsReturning(String fullyQualifiedTypeName) {
		this.fullyQualifiedTypeName = fullyQualifiedTypeName;
	}

	@Override
	public String getDisplayName() {
		return "지정한 타입을 반환하는 @Bean 메서드 탐지";
	}

	@Override
	public String getDescription() {
		return "반환 타입이 지정한 타입이거나 그 하위 타입인 @Bean 메서드를 표시한다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>("org.springframework.context.annotation.Bean", false),
				new JavaIsoVisitor<>() {
					@Override
					public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
							ExecutionContext ctx) {
						J.MethodDeclaration visited = super.visitMethodDeclaration(method, ctx);
						if (visited.getReturnTypeExpression() != null
								&& TypeUtils.isAssignableTo(FindBeanMethodsReturning.this.fullyQualifiedTypeName,
										visited.getReturnTypeExpression().getType())
								&& visited.getLeadingAnnotations().stream().anyMatch(BEAN::matches)) {
							return SearchResult.found(visited);
						}
						return visited;
					}
				});
	}

}
