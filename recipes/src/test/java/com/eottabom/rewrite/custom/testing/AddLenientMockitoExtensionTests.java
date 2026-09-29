package com.eottabom.rewrite.custom.testing;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class AddLenientMockitoExtensionTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new AddLenientMockitoExtension())
			.typeValidationOptions(TypeValidation.none())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package org.mockito; public @interface Mock {}",
						"package org.mockito; public @interface InjectMocks {}",
						"package org.junit.jupiter.api; public @interface Test {}",
						"package org.junit.jupiter.api.extension; public @interface ExtendWith { Class<?>[] value(); }",
						"package org.mockito.junit.jupiter; public class MockitoExtension {}",
						"package org.mockito; public class MockitoAnnotations { public static AutoCloseable openMocks(Object o) { return null; } }",
						"package org.junit.jupiter.api; public @interface Nested {}",
						"package org.springframework.test.context.junit.jupiter; public class SpringExtension {}"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void rewrites(String scenario, String before, String after) {
		rewriteRun((after != null) ? java(before, after) : java(before));
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		return Stream.of(
			Arguments.of(
				"adds lenient extension when missing",
				"""
				import org.junit.jupiter.api.Test;
				import org.mockito.InjectMocks;
				import org.mockito.Mock;

				class ServiceTest {
				    @Mock
				    Object repository;

				    @InjectMocks
				    Object service;

				    @Test
				    void test() {}
				}
				""",
				"""
				import org.junit.jupiter.api.Test;
				import org.junit.jupiter.api.extension.ExtendWith;
				import org.mockito.InjectMocks;
				import org.mockito.Mock;
				import org.mockito.junit.jupiter.MockitoExtension;
				import org.mockito.junit.jupiter.MockitoSettings;
				import org.mockito.quality.Strictness;

				@ExtendWith(MockitoExtension.class)
				@MockitoSettings(strictness = Strictness.LENIENT)
				class ServiceTest {
				    @Mock
				    Object repository;

				    @InjectMocks
				    Object service;

				    @Test
				    void test() {}
				}
				"""
			),
			Arguments.of(
				"leaves existing extend with alone",
				"""
				import org.junit.jupiter.api.Test;
				import org.junit.jupiter.api.extension.ExtendWith;
				import org.mockito.Mock;
				import org.mockito.junit.jupiter.MockitoExtension;

				@ExtendWith(MockitoExtension.class)
				class StrictTest {
				    @Mock
				    Object repository;

				    @Test
				    void test() {}
				}
				""",
				null
			),
			Arguments.of(
				"adds extension next to spring extension",
				"""
				import org.junit.jupiter.api.Test;
				import org.junit.jupiter.api.extension.ExtendWith;
				import org.mockito.Mock;
				import org.springframework.test.context.junit.jupiter.SpringExtension;

				@ExtendWith(SpringExtension.class)
				class SpringTest {
				    @Mock
				    Object repository;

				    @Test
				    void test() {}
				}
				""",
				"""
				import org.junit.jupiter.api.Test;
				import org.junit.jupiter.api.extension.ExtendWith;
				import org.mockito.Mock;
				import org.mockito.junit.jupiter.MockitoExtension;
				import org.mockito.junit.jupiter.MockitoSettings;
				import org.mockito.quality.Strictness;
				import org.springframework.test.context.junit.jupiter.SpringExtension;

				@ExtendWith(SpringExtension.class)
				@ExtendWith(MockitoExtension.class)
				@MockitoSettings(strictness = Strictness.LENIENT)
				class SpringTest {
				    @Mock
				    Object repository;

				    @Test
				    void test() {}
				}
				"""
			),
			Arguments.of(
				"leaves tests that open mocks themselves",
				"""
				import org.junit.jupiter.api.Test;
				import org.mockito.Mock;
				import org.mockito.MockitoAnnotations;

				class ManualTest {
				    @Mock
				    Object repository;

				    AutoCloseable mocks = MockitoAnnotations.openMocks(this);

				    @Test
				    void test() {}
				}
				""",
				null
			),
			Arguments.of(
				"leaves nested test inside class with extension",
				"""
				import org.junit.jupiter.api.Nested;
				import org.junit.jupiter.api.Test;
				import org.junit.jupiter.api.extension.ExtendWith;
				import org.mockito.Mock;
				import org.mockito.junit.jupiter.MockitoExtension;

				@ExtendWith(MockitoExtension.class)
				class OuterTest {
				    @Nested
				    class Inner {
				        @Mock
				        Object repository;

				        @Test
				        void test() {}
				    }
				}
				""",
				null
			)
		);
	}
	// @formatter:on

}
