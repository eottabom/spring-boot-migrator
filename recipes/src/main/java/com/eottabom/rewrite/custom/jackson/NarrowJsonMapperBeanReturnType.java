package com.eottabom.rewrite.custom.jackson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.eottabom.rewrite.support.SpringAnnotations;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

/**
 * JsonMapper 를 돌려주는 {@code @Bean ObjectMapper} 메서드의 반환 타입을 JsonMapper 로 바꾼다. Boot 4 는
 * JsonMapper 빈이 있을 때만 자동 설정을 물리므로, 그대로 두면 직접 만든 매퍼가 MVC 에 적용되지 않는다 (spring-boot#50870).
 */
public class NarrowJsonMapperBeanReturnType extends Recipe {

	private static final String OBJECT_MAPPER = "tools.jackson.databind.ObjectMapper";

	private static final String JSON_MAPPER = "tools.jackson.databind.json.JsonMapper";

	@Override
	public String getDisplayName() {
		return "@Bean ObjectMapper 의 반환 타입을 JsonMapper 로";
	}

	@Override
	public String getDescription() {
		return "Boot 4 는 JsonMapper 타입 빈이 있을 때만 자동 설정 매퍼가 물러난다. JsonMapper 를 돌려주는 @Bean ObjectMapper 메서드의 반환 타입을 JsonMapper 로 바꾼다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>(OBJECT_MAPPER, false), new JavaIsoVisitor<>() {
			@Override
			public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
				J.MethodDeclaration visited = super.visitMethodDeclaration(method, ctx);
				if (!isObjectMapperBean(visited)) {
					return visited;
				}
				JavaType.FullyQualified jsonMapper = returnsOnlyJsonMapper(Objects.requireNonNull(visited.getBody()));
				if (jsonMapper == null) {
					return visited;
				}
				maybeAddImport(JSON_MAPPER);
				maybeRemoveImport(OBJECT_MAPPER);
				J.Identifier returnType = new J.Identifier(Tree.randomId(),
						Objects.requireNonNull(visited.getReturnTypeExpression()).getPrefix(), Markers.EMPTY, List.of(),
						jsonMapper.getClassName(), jsonMapper, null);
				visited = visited.withReturnTypeExpression(returnType);
				return (visited.getMethodType() != null)
						? visited.withMethodType(visited.getMethodType().withReturnType(jsonMapper)) : visited;
			}
		});
	}

	private static boolean isObjectMapperBean(J.MethodDeclaration method) {
		return method.getBody() != null && method.getReturnTypeExpression() != null
				&& TypeUtils.isOfClassType(method.getReturnTypeExpression().getType(), OBJECT_MAPPER)
				&& method.getLeadingAnnotations().stream().anyMatch(SpringAnnotations.BEAN::matches);
	}

	/** return 이 모두 JsonMapper 이면 그 타입 (람다와 내부 클래스 제외) */
	private static JavaType.@Nullable FullyQualified returnsOnlyJsonMapper(J.Block body) {
		List<J.Return> returns = new ArrayList<>();
		new JavaIsoVisitor<List<J.Return>>() {
			@Override
			public J.Return visitReturn(J.Return returned, List<J.Return> found) {
				found.add(returned);
				return returned;
			}

			@Override
			public J.Lambda visitLambda(J.Lambda lambda, List<J.Return> found) {
				return lambda;
			}

			@Override
			public J.NewClass visitNewClass(J.NewClass newClass, List<J.Return> found) {
				return newClass;
			}

			@Override
			public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, List<J.Return> found) {
				return classDecl;
			}
		}.visit(body, returns);
		JavaType.FullyQualified jsonMapper = null;
		for (J.Return returned : returns) {
			JavaType.FullyQualified type = (returned.getExpression() != null)
					? TypeUtils.asFullyQualified(returned.getExpression().getType()) : null;
			if (type == null || !TypeUtils.isOfClassType(type, JSON_MAPPER)) {
				return null;
			}
			jsonMapper = type;
		}
		return jsonMapper;
	}

}
