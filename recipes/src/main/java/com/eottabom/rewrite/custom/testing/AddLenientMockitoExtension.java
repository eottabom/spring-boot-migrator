package com.eottabom.rewrite.custom.testing;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/**
 * Mockito 필드 어노테이션이 있는데 MockitoExtension 이나 openMocks 로 초기화하지 않는 테스트에 MockitoExtension 과
 * LENIENT 설정을 붙인다. {@code @ExtendWith(SpringExtension.class)} 만 있는 테스트도 대상이다. Boot 4 에서 이
 * 필드를 초기화하던 리스너가 빠졌고, strict stubs 로 기존 테스트가 깨지지 않도록 LENIENT 를 쓴다.
 */
public class AddLenientMockitoExtension extends Recipe {

	private static final AnnotationMatcher EXTEND_WITH = new AnnotationMatcher(
			"@org.junit.jupiter.api.extension.ExtendWith");

	private static final AnnotationMatcher MOCKITO_SETTINGS = new AnnotationMatcher(
			"@org.mockito.junit.jupiter.MockitoSettings");

	private static final String EXTEND_WITH_TYPE = "org.junit.jupiter.api.extension.ExtendWith";

	private static final List<AnnotationMatcher> MOCKITO_FIELD_ANNOTATIONS = List.of(
			new AnnotationMatcher("@org.mockito.Mock"), new AnnotationMatcher("@org.mockito.Spy"),
			new AnnotationMatcher("@org.mockito.InjectMocks"), new AnnotationMatcher("@org.mockito.Captor"));

	@Override
	public String getDisplayName() {
		return "Mockito 어노테이션 테스트에 Lenient MockitoExtension 추가";
	}

	@Override
	public String getDescription() {
		return "@Mock/@InjectMocks 필드가 있고 MockitoExtension 이나 openMocks 로 초기화하지 않는 JUnit 5 테스트에 "
				+ "@ExtendWith(MockitoExtension.class) 와 @MockitoSettings(strictness = Strictness.LENIENT) 를 추가한다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(Preconditions.and(new UsesType<>("org.mockito.*", false),
				new UsesType<>("org.junit.jupiter.api.*", false)), new JavaIsoVisitor<>() {
					@Override
					public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
							ExecutionContext ctx) {
						J.ClassDeclaration visited = super.visitClassDeclaration(classDecl, ctx);
						// @ExtendWith(SpringExtension.class) 처럼 다른 확장만 있는 테스트도 필드를 초기화할
						// 리스너가 없다
						if (!hasMockitoFields(visited) || initializesMocks(visited) || enclosedByInitializedClass()
								|| supertypeExtends(visited.getType())) {
							return visited;
						}
						maybeAddImport("org.junit.jupiter.api.extension.ExtendWith");
						maybeAddImport("org.mockito.junit.jupiter.MockitoExtension");
						maybeAddImport("org.mockito.junit.jupiter.MockitoSettings");
						maybeAddImport("org.mockito.quality.Strictness");
						return JavaTemplate.builder(
								"@ExtendWith(MockitoExtension.class)\n@MockitoSettings(strictness = Strictness.LENIENT)")
							.imports("org.junit.jupiter.api.extension.ExtendWith",
									"org.mockito.junit.jupiter.MockitoExtension",
									"org.mockito.junit.jupiter.MockitoSettings", "org.mockito.quality.Strictness")
							.javaParser(JavaParser.fromJavaVersion()
								.dependsOn(
										"package org.junit.jupiter.api.extension; public @interface ExtendWith { Class<?>[] value(); }",
										"package org.mockito.junit.jupiter; public class MockitoExtension {}",
										"package org.mockito.quality; public enum Strictness { LENIENT, WARN, STRICT_STUBS }",
										"package org.mockito.junit.jupiter; public @interface MockitoSettings { org.mockito.quality.Strictness strictness(); }"))
							.build()
							.apply(updateCursor(visited), visited.getCoordinates()
								.addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
					}

					/**
					 * MockitoExtension 이나 MockitoSettings 가 있거나 openMocks / initMocks 를
					 * 직접 부른다
					 */
					private boolean initializesMocks(J.ClassDeclaration classDecl) {
						boolean extension = classDecl.getLeadingAnnotations()
							.stream()
							.anyMatch((annotation) -> MOCKITO_SETTINGS.matches(annotation)
									|| (EXTEND_WITH.matches(annotation)
											&& annotation.print(getCursor()).contains("MockitoExtension")));
						return extension || callsOpenMocks(classDecl);
					}

					private boolean callsOpenMocks(J.ClassDeclaration classDecl) {
						AtomicBoolean found = new AtomicBoolean();
						new JavaIsoVisitor<AtomicBoolean>() {
							@Override
							public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
									AtomicBoolean calls) {
								if (method.getSimpleName().equals("openMocks")
										|| method.getSimpleName().equals("initMocks")) {
									calls.set(true);
								}
								return super.visitMethodInvocation(method, calls);
							}
						}.visit(classDecl.getBody(), found);
						return found.get();
					}

					/** @Nested 테스트는 바깥 클래스의 확장을 물려받는다 */
					private boolean enclosedByInitializedClass() {
						for (Cursor parent = getCursor().getParent(); parent != null; parent = parent.getParent()) {
							if (parent.getValue() instanceof J.ClassDeclaration outer && initializesMocks(outer)) {
								return true;
							}
						}
						return false;
					}

					/** 상위 클래스의 @ExtendWith 는 값을 알 수 없어 확장이 있다고 본다 */
					private boolean supertypeExtends(JavaType.@Nullable FullyQualified type) {
						for (JavaType.FullyQualified parent = (type != null) ? type.getSupertype()
								: null; parent != null
										&& !parent.getFullyQualifiedName().equals("java.lang.Object"); parent = parent
											.getSupertype()) {
							if (parent.getAnnotations()
								.stream()
								.anyMatch(
										(annotation) -> annotation.getFullyQualifiedName().equals(EXTEND_WITH_TYPE))) {
								return true;
							}
						}
						return false;
					}

					private boolean hasMockitoFields(J.ClassDeclaration classDecl) {
						return classDecl.getBody()
							.getStatements()
							.stream()
							.filter(J.VariableDeclarations.class::isInstance)
							.flatMap((field) -> ((J.VariableDeclarations) field).getLeadingAnnotations().stream())
							.anyMatch((annotation) -> MOCKITO_FIELD_ANNOTATIONS.stream()
								.anyMatch((matcher) -> matcher.matches(annotation)));
					}
				});
	}

}
