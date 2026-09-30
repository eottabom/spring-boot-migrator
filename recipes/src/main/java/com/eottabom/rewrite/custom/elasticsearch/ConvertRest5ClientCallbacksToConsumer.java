package com.eottabom.rewrite.custom.elasticsearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Rest5ClientBuilder 의 콜백은 Consumer 라서 builder 를 돌려주던 람다 끝의 {@code return builder} 를 없앤다.
 * 블록 마지막 return 만 바꾸고, 중간에 return 하는 콜백은 컴파일 에러로 드러나게 둔다.
 */
public class ConvertRest5ClientCallbacksToConsumer extends Recipe {

	private static final List<String> BUILDERS = Arrays.asList("org.elasticsearch.client.RestClientBuilder",
			"co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder");

	private static final List<String> CALLBACKS = Arrays.asList("setHttpClientConfigCallback",
			"setRequestConfigCallback");

	@Override
	public String getDisplayName() {
		return "Rest5ClientBuilder 콜백을 Consumer 형태로";
	}

	@Override
	public String getDescription() {
		return "Rest5Client 빌더의 setHttpClientConfigCallback / setRequestConfigCallback 에 넘기는 람다 끝의 return builder 를 없앤다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<>() {
			@Override
			public J.Lambda visitLambda(J.Lambda lambda, ExecutionContext ctx) {
				J.Lambda callback = super.visitLambda(lambda, ctx);
				if (!(getCursor().getParentTreeCursor().getValue() instanceof J.MethodInvocation invocation)
						|| !isBuilderCallback(invocation) || !(callback.getBody() instanceof J.Block body)) {
					return callback;
				}
				List<Statement> statements = body.getStatements();
				if (statements.isEmpty() || !(statements.get(statements.size() - 1) instanceof J.Return returned)) {
					return callback;
				}
				Expression value = returned.getExpression();
				var withoutReturn = new ArrayList<>(statements.subList(0, statements.size() - 1));
				if (value instanceof J.MethodInvocation call) {
					// return builder.setX(..); → builder.setX(..);
					withoutReturn.add(call.withPrefix(returned.getPrefix()));
				}
				else if (!isLambdaParameter(callback, value)) {
					return callback;
				}
				return callback.withBody(body.withStatements(withoutReturn));
			}

			private boolean isBuilderCallback(J.MethodInvocation invocation) {
				JavaType.Method type = invocation.getMethodType();
				return CALLBACKS.contains(invocation.getSimpleName()) && type != null && BUILDERS.stream()
					.anyMatch((builder) -> TypeUtils.isOfClassType(type.getDeclaringType(), builder));
			}

			private boolean isLambdaParameter(J.Lambda callback, @Nullable Expression value) {
				if (!(value instanceof J.Identifier returned) || callback.getParameters().getParameters().size() != 1) {
					return false;
				}
				J parameter = callback.getParameters().getParameters().get(0);
				String name = (parameter instanceof J.VariableDeclarations declaration)
						? declaration.getVariables().get(0).getSimpleName()
						: (parameter instanceof J.Identifier identifier) ? identifier.getSimpleName() : null;
				return returned.getSimpleName().equals(name);
			}
		};
	}

}
