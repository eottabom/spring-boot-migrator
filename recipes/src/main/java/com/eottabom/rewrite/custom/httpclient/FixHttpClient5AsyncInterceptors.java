package com.eottabom.rewrite.custom.httpclient;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

/**
 * upstream UpgradeApacheHttpClient_5 가 남기는 async 빌더 인터셉터 차이를 맞춘다.
 * {@code addInterceptorLast/First} 를 response, request 이름으로 나누고, 인터셉터 람다에 HttpClient 5 의
 * entity 인자를 넣는다.
 */
public class FixHttpClient5AsyncInterceptors extends Recipe {

	private static final String RESPONSE_INTERCEPTOR = "org.apache.hc.core5.http.HttpResponseInterceptor";

	private static final String REQUEST_INTERCEPTOR = "org.apache.hc.core5.http.HttpRequestInterceptor";

	@Override
	public String getDisplayName() {
		return "HttpClient 5 비동기 인터셉터 API 보정";
	}

	@Override
	public String getDescription() {
		return "HttpClient 5 이름으로 addInterceptorLast/First 를 바꾸고, 인터셉터 람다에 entity 인자를 추가한다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<>() {
			@Override
			public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
				J.MethodInvocation visited = super.visitMethodInvocation(method, ctx);
				if (("addInterceptorLast".equals(visited.getSimpleName())
						|| "addInterceptorFirst".equals(visited.getSimpleName()))
						&& visited.getArguments().size() == 1) {
					String suffix = visited.getSimpleName().endsWith("Last") ? "Last" : "First";
					JavaType argType = interceptorType(visited.getArguments().get(0));
					if (TypeUtils.isOfClassType(argType, RESPONSE_INTERCEPTOR)) {
						return visited.withName(visited.getName().withSimpleName("addResponseInterceptor" + suffix));
					}
					if (TypeUtils.isOfClassType(argType, REQUEST_INTERCEPTOR)) {
						return visited.withName(visited.getName().withSimpleName("addRequestInterceptor" + suffix));
					}
				}
				return visited;
			}

			@Override
			public J.Lambda visitLambda(J.Lambda lambda, ExecutionContext ctx) {
				J.Lambda visited = super.visitLambda(lambda, ctx);
				J parent = getCursor().getParentTreeCursor().getValue();
				JavaType target = (parent instanceof J.TypeCast typeCast) ? typeCast.getClazz().getTree().getType()
						: visited.getType();
				boolean interceptor = TypeUtils.isOfClassType(target, RESPONSE_INTERCEPTOR)
						|| TypeUtils.isOfClassType(target, REQUEST_INTERCEPTOR);
				if (!interceptor || visited.getParameters().getParameters().size() != 2) {
					return visited;
				}
				List<JRightPadded<J>> params = new ArrayList<>(visited.getParameters().getPadding().getParameters());
				J first = params.get(0).getElement();
				J entity = (first instanceof J.Identifier identifier)
						? identifier.withId(Tree.randomId()).withSimpleName("entity").withPrefix(Space.SINGLE_SPACE)
						: new J.Identifier(Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, new ArrayList<>(),
								"entity", null, null);
				params.add(1, JRightPadded.build(entity));
				return visited.withParameters(visited.getParameters().getPadding().withParameters(params));
			}

			private @Nullable JavaType interceptorType(Expression arg) {
				return (arg instanceof J.TypeCast typeCast) ? typeCast.getClazz().getTree().getType() : arg.getType();
			}
		};
	}

}
