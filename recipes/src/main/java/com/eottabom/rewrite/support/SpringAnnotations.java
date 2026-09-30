package com.eottabom.rewrite.support;

import org.openrewrite.java.AnnotationMatcher;

/** Spring 어노테이션을 찾는 레시피가 같이 쓰는 matcher. */
public final class SpringAnnotations {

	public static final AnnotationMatcher BEAN = new AnnotationMatcher("@org.springframework.context.annotation.Bean");

	private SpringAnnotations() {
	}

}
