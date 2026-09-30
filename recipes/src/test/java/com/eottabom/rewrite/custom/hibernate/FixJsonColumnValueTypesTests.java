package com.eottabom.rewrite.custom.hibernate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class FixJsonColumnValueTypesTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new FixJsonColumnValueTypes())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn(
						"package org.hibernate.annotations; public @interface Type { Class<?> value(); String[] parameters() default {}; }",
						"package io.hypersistence.utils.hibernate.type.json; public class JsonStringType {}",
						"package lombok; public @interface Getter {}",
						"package lombok; public @interface EqualsAndHashCode {}",
						"package org.hibernate.annotations; public @interface JdbcTypeCode { int value(); }",
						"package org.hibernate.type; public class SqlTypes { public static final int JSON = 3001; public static final int OTHER = 1111; }",
						"package com.vladmihalcea.hibernate.type.json; public class JsonType {}",
						"package io.hypersistence.utils.hibernate.type.array; public class ListArrayType {}",
						"package com.example.types; public class MoneyType {}"));
	}

	@Test
	void addsSerializableToJsonAttributeTypesAndChildren() {
		rewriteRun(java("""
				package com.example;
				import io.hypersistence.utils.hibernate.type.json.JsonStringType;
				import org.hibernate.annotations.Type;
				import java.util.List;
				public class Event {
				    @Type(JsonStringType.class)
				    private List<Condition> conditions;
				    @Type(JsonStringType.class)
				    private EtcCondition etc;
				    private Plain notJson;
				}
				"""), java("""
				package com.example;
				public class Condition {
				    private Detail detail;
				}
				""", """
				package com.example;

				import java.io.Serializable;

				public class Condition implements Serializable {
				    private Detail detail;
				}
				"""), java("""
				package com.example;
				public class Detail {
				    private String name;
				}
				""", """
				package com.example;

				import java.io.Serializable;

				public class Detail implements Serializable {
				    private String name;
				}
				"""), java("""
				package com.example;
				public class EtcCondition {
				    private boolean excludeReservation;
				}
				""", """
				package com.example;

				import java.io.Serializable;

				public class EtcCondition implements Serializable {
				    private boolean excludeReservation;
				}
				"""), java("""
				package com.example;
				public class Plain {
				    private String value;
				}
				"""));
	}

	@Test
	void addsEqualsToLombokValueObjects() {
		rewriteRun(java("""
				package com.example;
				import io.hypersistence.utils.hibernate.type.json.JsonStringType;
				import org.hibernate.annotations.Type;
				import java.util.List;
				public class Event {
				    @Type(JsonStringType.class)
				    private List<Item> items;
				    @Type(JsonStringType.class)
				    private Single single;
				}
				"""), java("""
				package com.example;
				import lombok.Getter;
				@Getter
				public class Item {
				    private String code;
				}
				""", """
				package com.example;
				import lombok.EqualsAndHashCode;
				import lombok.Getter;

				import java.io.Serializable;

				@EqualsAndHashCode
				@Getter
				public class Item implements Serializable {
				    private String code;
				}
				"""), java("""
				package com.example;
				import lombok.Getter;
				@Getter
				public class Single {
				    private String code;
				}
				""", """
				package com.example;
				import lombok.EqualsAndHashCode;
				import lombok.Getter;

				import java.io.Serializable;

				@EqualsAndHashCode
				@Getter
				public class Single implements Serializable {
				    private String code;
				}
				"""));
	}

	@Test
	void skipsEqualsWhenClassExtendsAnother() {
		rewriteRun(java("""
				package com.example;
				import io.hypersistence.utils.hibernate.type.json.JsonStringType;
				import org.hibernate.annotations.Type;
				public class Holder {
				    @Type(JsonStringType.class)
				    private Child child;
				}
				"""), java("""
				package com.example;
				public class Base implements java.io.Serializable {
				    private String common;
				}
				"""), java("""
				package com.example;
				import lombok.Getter;
				@Getter
				public class Child extends Base {
				    private String code;
				}
				"""));
	}

	@ParameterizedTest(name = "[{index}] {0} -> {1}")
	@CsvSource(delimiter = '|',
			value = { "@Type(value = JsonType.class)|true", "@JdbcTypeCode(SqlTypes.JSON)|true",
					"@Type(ListArrayType.class)|false", "@Type(MoneyType.class)|false",
					"@Type(value = MoneyType.class, parameters = {})|false", "@Type(value = int.class)|false",
					"@JdbcTypeCode(SqlTypes.OTHER)|false", "@JdbcTypeCode(3001)|false", "@Deprecated|false",
					"@SuppressWarnings(\"unused\")|false" })
	void treatsOnlyJsonMappingsAsJsonAttributes(String annotation, boolean json) {
		String value = """
				package com.example;
				public class Value {
				    private String text;
				}
				""";
		rewriteRun(java("""
				package com.example;
				import com.example.types.MoneyType;
				import com.vladmihalcea.hibernate.type.json.JsonType;
				import io.hypersistence.utils.hibernate.type.array.ListArrayType;
				import org.hibernate.annotations.JdbcTypeCode;
				import org.hibernate.annotations.Type;
				import org.hibernate.type.SqlTypes;
				public class Event {
				    %s
				    private Value value;
				}
				""".formatted(annotation)), json ? java(value, """
				package com.example;

				import java.io.Serializable;

				public class Value implements Serializable {
				    private String text;
				}
				""") : java(value));
	}

	@Test
	void followsArraysAndSelfReferencesButSkipsEnumsAndRecords() {
		rewriteRun(java("""
				package com.example;
				import io.hypersistence.utils.hibernate.type.json.JsonStringType;
				import org.hibernate.annotations.Type;
				public class Event {
				    @Type(JsonStringType.class)
				    private Node[] nodes;
				    @Type(JsonStringType.class)
				    private Kind kind;
				    @Type(JsonStringType.class)
				    private Point point;
				}
				"""), java("""
				package com.example;
				public class Node {
				    private Node parent;
				    public String toString() {
				        return "node";
				    }
				    public boolean equals(Object other) {
				        return other == this;
				    }
				}
				""", """
				package com.example;

				import java.io.Serializable;

				public class Node implements Serializable {
				    private Node parent;
				    public String toString() {
				        return "node";
				    }
				    public boolean equals(Object other) {
				        return other == this;
				    }
				}
				"""), java("""
				package com.example;
				public enum Kind { A }
				"""), java("""
				package com.example;
				public record Point(int x) {}
				"""));
	}

}
