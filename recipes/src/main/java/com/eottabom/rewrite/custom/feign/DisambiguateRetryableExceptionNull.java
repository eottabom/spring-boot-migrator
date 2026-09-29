package com.eottabom.rewrite.custom.feign;

import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Feign 12 에서 RetryableException 생성자의 retryAfter 가 Date 와 Long 두 가지가 되어 null 인자가 모호해진 것을
 * {@code (Long) null} 로 바꾼다 (reference to RetryableException is ambiguous).
 */
public class DisambiguateRetryableExceptionNull extends Recipe {

	private static final String RETRYABLE_EXCEPTION = "feign.RetryableException";

	@Override
	public String getDisplayName() {
		return "Feign RetryableException 생성자 인자 모호성 제거";
	}

	@Override
	public String getDescription() {
		return "RetryableException 생성자(..., null, request) 의 retryAfter null 을 (Long) null 로 바꾼다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>(RETRYABLE_EXCEPTION, false), new JavaIsoVisitor<>() {
			@Override
			public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
				J.NewClass visited = super.visitNewClass(newClass, ctx);
				if (!TypeUtils.isOfClassType(visited.getType(), RETRYABLE_EXCEPTION)) {
					return visited;
				}
				List<Expression> args = visited.getArguments();
				// (status, message, method, cause, retryAfter, request)
				if (args.size() != 6 || !isNullLiteral(args.get(4))) {
					return visited;
				}
				Expression retryAfter = args.get(4);
				return JavaTemplate.builder("(Long) null")
					.build()
					.apply(updateCursor(visited), retryAfter.getCoordinates().replace());
			}

			private boolean isNullLiteral(Expression expression) {
				return expression instanceof J.Literal literal && literal.getValue() == null
						&& literal.getType() == JavaType.Primitive.Null;
			}
		});
	}

}
