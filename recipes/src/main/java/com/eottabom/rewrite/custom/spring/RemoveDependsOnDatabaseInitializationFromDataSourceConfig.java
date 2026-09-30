package com.eottabom.rewrite.custom.spring;

import java.util.Arrays;
import java.util.List;

import com.eottabom.rewrite.support.SpringAnnotations;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.RemoveAnnotationVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * DataSource 를 만드는 설정 빈(HikariConfig 등)에서 {@code @DependsOnDatabaseInitialization} 을 뺀다.
 * upstream 이 붙이면 DB 초기화 빈과 DataSource 사이에 순환 참조가 생긴다 (BeanCurrentlyInCreationException).
 */
public class RemoveDependsOnDatabaseInitializationFromDataSourceConfig extends Recipe {

	private static final String DEPENDS_ON = "org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization";

	private static final AnnotationMatcher DEPENDS_ON_MATCHER = new AnnotationMatcher("@" + DEPENDS_ON);

	private static final List<String> DATA_SOURCE_CONFIG_TYPES = Arrays.asList("com.zaxxer.hikari.HikariConfig",
			"org.springframework.boot.autoconfigure.jdbc.DataSourceProperties",
			"org.springframework.boot.jdbc.autoconfigure.DataSourceProperties", "javax.sql.DataSource");

	@Override
	public String getDisplayName() {
		return "DataSource 설정 빈의 @DependsOnDatabaseInitialization 제거";
	}

	@Override
	public String getDescription() {
		return "HikariConfig / DataSourceProperties / DataSource 를 반환하는 @Bean 메서드에서 @DependsOnDatabaseInitialization 을 제거해 "
				+ "DB 초기화 빈과의 순환 참조를 막는다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>(DEPENDS_ON, false), new JavaIsoVisitor<>() {
			@Override
			public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
				J.MethodDeclaration visited = super.visitMethodDeclaration(method, ctx);
				if (visited.getLeadingAnnotations().stream().noneMatch(SpringAnnotations.BEAN::matches)
						|| visited.getLeadingAnnotations().stream().noneMatch(DEPENDS_ON_MATCHER::matches)
						|| !isDataSourceConfig((visited.getReturnTypeExpression() == null) ? null
								: visited.getReturnTypeExpression().getType())) {
					return visited;
				}
				visited = (J.MethodDeclaration) new RemoveAnnotationVisitor(DEPENDS_ON_MATCHER).visitNonNull(visited,
						ctx, getCursor().getParentOrThrow());
				maybeRemoveImport(DEPENDS_ON);
				return visited;
			}

			private boolean isDataSourceConfig(@Nullable JavaType type) {
				return DATA_SOURCE_CONFIG_TYPES.stream()
					.anyMatch((configType) -> TypeUtils.isAssignableTo(configType, type));
			}
		});
	}

}
