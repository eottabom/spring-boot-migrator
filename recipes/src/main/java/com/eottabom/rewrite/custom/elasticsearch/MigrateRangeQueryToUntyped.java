package com.eottabom.rewrite.custom.elasticsearch;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * elasticsearch-java 8.15 에서 RangeQuery.Builder 의 field, gte, lte 등이
 * UntypedRangeQuery.Builder 로 옮겨진 것을 따라간다. 8.14 이하 API 로 쓴 코드만 바꾸고, 람다로 쓰는
 * {@code q.range(r -> ...)} 는 컴파일 에러로 드러나게 둔다.
 */
public class MigrateRangeQueryToUntyped extends Recipe {

	private static final String RANGE_QUERY_BUILDER = "co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery$Builder";

	private static final String UNTYPED_RANGE_QUERY = "co.elastic.clients.elasticsearch._types.query_dsl.UntypedRangeQuery";

	private static final String UNTYPED_RANGE_QUERY_BUILDER = UNTYPED_RANGE_QUERY + "$Builder";

	@Override
	public String getDisplayName() {
		return "Elasticsearch Java 8.15 RangeQuery 를 UntypedRangeQuery 로 변경";
	}

	@Override
	public String getDescription() {
		return "RangeQuery.Builder 변수를 UntypedRangeQuery.Builder 로 바꾸고, build() 결과를 _toRangeQuery() 로 RangeQuery 로 감싼다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>("co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery", true),
				new JavaIsoVisitor<>() {
					private boolean changed;

					@Override
					public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
						this.changed = false;
						J.CompilationUnit visited = super.visitCompilationUnit(cu, ctx);
						if (this.changed) {
							doAfterVisit(new ChangeType(RANGE_QUERY_BUILDER, UNTYPED_RANGE_QUERY_BUILDER, true)
								.getVisitor());
							// 중첩 클래스로 바꾸면 ChangeType 이 바깥 클래스 import 를 추가하지 않는다
							doAfterVisit(new AddImport<>(UNTYPED_RANGE_QUERY, null, false));
						}
						return visited;
					}

					@Override
					public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
						J.NewClass visited = super.visitNewClass(newClass, ctx);
						if (isLegacyBuilder(visited.getType())) {
							this.changed = true;
						}
						return visited;
					}

					@Override
					public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
						J.MethodInvocation visited = super.visitMethodInvocation(method, ctx);
						if (!"build".equals(visited.getSimpleName()) || visited.getSelect() == null
								|| !isLegacyBuilder(visited.getSelect().getType())) {
							return visited;
						}
						this.changed = true;
						// build() 가 UntypedRangeQuery 를 돌려주므로 RangeQuery 로 감싼다
						return JavaTemplate.builder("#{any()}._toRangeQuery()")
							.build()
							.apply(new Cursor(getCursor().getParent(), visited), visited.getCoordinates().replace(),
									visited);
					}

					private boolean isLegacyBuilder(@Nullable JavaType type) {
						JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
						return fq != null && TypeUtils.isOfClassType(fq, RANGE_QUERY_BUILDER.replace('$', '.'))
								&& fq.getMethods().stream().anyMatch((mt) -> "field".equals(mt.getName()));
					}
				});
	}

}
