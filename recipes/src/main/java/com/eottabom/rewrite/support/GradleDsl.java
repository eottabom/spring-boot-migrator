package com.eottabom.rewrite.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.openrewrite.Cursor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

/**
 * Gradle 빌드 스크립트(Groovy, Kotlin 모두 J 트리로 보인다)를 다루는 레시피가 같이 쓰는 동작.
 */
public final class GradleDsl {

	/** 이 블록 안의 좌표는 의존성 선언이 아니다 (플러그인 classpath, 버전 제약, resolve 규칙) */
	private static final Set<String> NOT_DECLARATIONS = Set.of("buildscript", "constraints", "resolutionStrategy",
			"configurations");

	private GradleDsl() {
	}

	/** dependencies { } 안의 의존성 선언 자리인지 */
	public static boolean inDependenciesBlock(Cursor cursor) {
		boolean dependencies = false;
		for (Cursor parent = cursor.getParent(); parent != null; parent = parent.getParent()) {
			if (parent.getValue() instanceof J.MethodInvocation invocation) {
				String name = invocation.getSimpleName();
				if (NOT_DECLARATIONS.contains(name)) {
					return false;
				}
				dependencies |= name.equals("dependencies");
			}
		}
		return dependencies;
	}

	/** 첫 인자만 바꾼 인자 목록 */
	public static List<Expression> withFirstArgument(List<Expression> arguments, Expression first) {
		List<Expression> replaced = new ArrayList<>(arguments);
		replaced.set(0, first);
		return replaced;
	}

	/** 문자열 값만 바꾼다. 원래 쓰던 따옴표(작은따옴표, 큰따옴표)는 그대로 둔다 */
	public static J.Literal withStringValue(J.Literal literal, String value) {
		String quote = quoteOf(literal);
		return literal.withValue(value).withValueSource(quote + value + quote);
	}

	/** 문자열 리터럴이 쓰는 따옴표. 소스 표기를 모르면 큰따옴표 */
	public static String quoteOf(J.Literal literal) {
		String source = literal.getValueSource();
		return (source != null && !source.isEmpty()) ? source.substring(0, 1) : "\"";
	}

}
