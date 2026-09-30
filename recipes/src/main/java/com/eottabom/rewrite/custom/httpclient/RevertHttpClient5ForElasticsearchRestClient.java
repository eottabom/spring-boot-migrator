package com.eottabom.rewrite.custom.httpclient;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Elasticsearch 저수준 RestClient 는 HttpClient 4 API 를 쓰는데 upstream
 * UpgradeApacheHttpClient_5 가 그 설정 코드까지 HttpClient 5 로 바꾼다. RestClient 를 쓰는 파일에서만 타입과 생성자
 * 호출을 HttpClient 4 로 되돌린다.
 */
public class RevertHttpClient5ForElasticsearchRestClient extends Recipe {

	private static final String REST_CLIENT = "org.elasticsearch.client.RestClient";

	private static final List<TypeMapping> TYPES = List.of(
			new TypeMapping("org.apache.hc.core5.http.HttpHost", "org.apache.http.HttpHost"),
			new TypeMapping("org.apache.hc.core5.http.Header", "org.apache.http.Header"),
			new TypeMapping("org.apache.hc.core5.http.HttpHeaders", "org.apache.http.HttpHeaders"),
			new TypeMapping("org.apache.hc.core5.http.HttpResponseInterceptor",
					"org.apache.http.HttpResponseInterceptor"),
			new TypeMapping("org.apache.hc.core5.http.HttpRequestInterceptor",
					"org.apache.http.HttpRequestInterceptor"),
			new TypeMapping("org.apache.hc.core5.http.message.BasicHeader", "org.apache.http.message.BasicHeader"),
			new TypeMapping("org.apache.hc.client5.http.auth.AuthScope", "org.apache.http.auth.AuthScope"),
			new TypeMapping("org.apache.hc.client5.http.auth.UsernamePasswordCredentials",
					"org.apache.http.auth.UsernamePasswordCredentials"),
			new TypeMapping("org.apache.hc.client5.http.auth.CredentialsStore",
					"org.apache.http.client.CredentialsProvider"),
			new TypeMapping("org.apache.hc.client5.http.auth.CredentialsProvider",
					"org.apache.http.client.CredentialsProvider"),
			new TypeMapping("org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider",
					"org.apache.http.impl.client.BasicCredentialsProvider"));

	@Override
	public String getDisplayName() {
		return "Elasticsearch RestClient 코드의 HttpClient 5 전환 되돌리기";
	}

	@Override
	public String getDescription() {
		return "Elasticsearch RestClient(org.elasticsearch.client.RestClient) 를 쓰는 파일에서 upstream 이 바꾼 HttpClient 5 타입과 호출을 HttpClient 4 로 되돌린다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>(REST_CLIENT, false), new TreeVisitor<>() {
			@Override
			public Tree preVisit(Tree tree, ExecutionContext ctx) {
				stopAfterPreVisit();
				Tree reverted = tree;
				for (TypeMapping type : TYPES) {
					reverted = new ChangeType(type.httpClient5(), type.httpClient4(), true).getVisitor()
						.visitNonNull(reverted, ctx);
				}
				return new ExpressionFixes().visitNonNull(reverted, ctx);
			}
		});
	}

	private record TypeMapping(String httpClient5, String httpClient4) {
	}

	private static class ExpressionFixes extends JavaVisitor<ExecutionContext> {

		private static final String AUTH_SCOPE = "org.apache.http.auth.AuthScope";

		@Override
		public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
			J.NewClass visited = (J.NewClass) super.visitNewClass(newClass, ctx);
			if (isAnyAuthScope(visited)) {
				return anyAuthScope(visited);
			}
			Expression password = charArrayPassword(visited);
			if (password != null) {
				return visited.withArguments(List.of(visited.getArguments().get(0), password));
			}
			return isHttpClient5Host(visited) ? httpClient4HostOrder(visited) : visited;
		}

		private static boolean isAnyAuthScope(J.NewClass newClass) {
			List<Expression> args = newClass.getArguments();
			return TypeUtils.isOfClassType(newClass.getType(), AUTH_SCOPE) && args.size() == 2 && isNull(args.get(0))
					&& isMinusOne(args.get(1));
		}

		private static @Nullable Expression charArrayPassword(J.NewClass newClass) {
			List<Expression> args = newClass.getArguments();
			if (!TypeUtils.isOfClassType(newClass.getType(), "org.apache.http.auth.UsernamePasswordCredentials")
					|| args.size() != 2 || !(args.get(1) instanceof J.MethodInvocation call)
					|| !"toCharArray".equals(call.getSimpleName())) {
				return null;
			}
			Expression password = call.getSelect();
			return (password != null) ? password.withPrefix(call.getPrefix()) : null;
		}

		private static boolean isHttpClient5Host(J.NewClass newClass) {
			List<Expression> args = newClass.getArguments();
			return TypeUtils.isOfClassType(newClass.getType(), "org.apache.http.HttpHost") && args.size() == 3
					&& isString(args.get(0)) && isString(args.get(1)) && isInt(args.get(2));
		}

		private J anyAuthScope(J.NewClass authScope) {
			return JavaTemplate.builder("AuthScope.ANY")
				.imports(AUTH_SCOPE)
				.javaParser(JavaParser.fromJavaVersion()
					.dependsOn(
							"package org.apache.http.auth; public class AuthScope { public static final AuthScope ANY = null; }"))
				.build()
				.apply(getCursor(), authScope.getCoordinates().replace());
		}

		/**
		 * (scheme, host, port) 를 (host, port, scheme) 으로 바꾸고 생성자 타입의 파라미터 순서도 되돌린다. 남겨 두면
		 * 4.0 에서 upstream 이 다시 바꿀 때 ReorderMethodArguments 가 맞지 않는다.
		 */
		private static J.NewClass httpClient4HostOrder(J.NewClass httpHost) {
			List<Expression> args = httpHost.getArguments();
			Expression scheme = args.get(0);
			Expression host = args.get(1);
			Expression port = args.get(2);
			J.NewClass reordered = httpHost.withArguments(List.of(host.withPrefix(scheme.getPrefix()),
					port.withPrefix(host.getPrefix()), scheme.withPrefix(port.getPrefix())));
			return Optional.ofNullable(httpHost.getConstructorType())
				.map((constructor) -> reordered.withConstructorType(hostPortScheme(constructor)))
				.orElse(reordered);
		}

		/** 3개 인자 생성자와 맞춘 타입이라 파라미터도 3개다 */
		private static JavaType.Method hostPortScheme(JavaType.Method constructor) {
			List<JavaType> types = constructor.getParameterTypes();
			List<String> names = constructor.getParameterNames();
			return constructor.withParameterTypes(List.of(types.get(1), types.get(2), types.get(0)))
				.withParameterNames(List.of(names.get(1), names.get(2), names.get(0)));
		}

		private static boolean isNull(Expression expression) {
			return expression instanceof J.Literal literal && literal.getValue() == null;
		}

		private static boolean isMinusOne(Expression expression) {
			return expression instanceof J.Literal literal && Integer.valueOf(-1).equals(literal.getValue());
		}

		private static boolean isString(Expression expression) {
			return TypeUtils.isString(expression.getType());
		}

		private static boolean isInt(Expression expression) {
			return expression.getType() == JavaType.Primitive.Int
					|| TypeUtils.isOfClassType(expression.getType(), "java.lang.Integer");
		}

	}

}
