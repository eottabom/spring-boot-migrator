package com.eottabom.rewrite.custom.hibernate;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.ImplementInterface;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * JSON 컬럼(Hypersistence {@code @Type}, Hibernate {@code @JdbcTypeCode(SqlTypes.JSON)})에
 * 매핑된 값 객체와 그 하위 객체를 보정한다.
 * <ul>
 * <li>Serializable 이 아니면 flush 때 NonSerializableObjectException 이 나서
 * {@code implements Serializable} 을 붙인다</li>
 * <li>equals 가 없으면 값이 같아도 변경으로 보고 UPDATE 가 나가서 {@code @EqualsAndHashCode} 를 붙인다 (Lombok 을
 * 쓰고 부모가 없는 클래스만)</li>
 * </ul>
 */
public class FixJsonColumnValueTypes extends ScanningRecipe<FixJsonColumnValueTypes.Accumulator> {

	private static final String HIBERNATE_TYPE = "org.hibernate.annotations.Type";

	private static final String SERIALIZABLE = "java.io.Serializable";

	@Override
	public String getDisplayName() {
		return "JSON 컬럼 값 객체의 직렬화와 동등성 보정";
	}

	@Override
	public String getDescription() {
		return "@Type(Json*Type.class) 로 매핑된 엔티티 속성의 타입(제네릭 인자 포함)과 그 하위 필드 타입 중 "
				+ "프로젝트 소스에 있는 클래스에 implements Serializable 을 추가하고, equals 가 없으면 @EqualsAndHashCode 를 붙여 "
				+ "유령 UPDATE 를 막는다. @JdbcTypeCode(SqlTypes.JSON) 속성도 대상이다.";
	}

	@Override
	public Accumulator getInitialValue(ExecutionContext ctx) {
		return new Accumulator();
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
		return new JavaIsoVisitor<>() {
			@Override
			public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
				Optional.ofNullable(classDecl.getType()).ifPresent((type) -> {
					Set<String> fieldTypes = acc.fieldTypesByClass.computeIfAbsent(type.getFullyQualifiedName(),
							(className) -> new HashSet<>());
					type.getMembers().forEach((member) -> collectTypes(member.getType(), fieldTypes));
				});
				return super.visitClassDeclaration(classDecl, ctx);
			}

			@Override
			public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable,
					ExecutionContext ctx) {
				if (multiVariable.getLeadingAnnotations()
					.stream()
					.anyMatch(FixJsonColumnValueTypes::isJsonTypeAnnotation)) {
					collectTypes(multiVariable.getType(), acc.roots);
				}
				return super.visitVariableDeclarations(multiVariable, ctx);
			}
		};
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
		return new JavaIsoVisitor<>() {
			@Override
			public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
				J.ClassDeclaration visited = super.visitClassDeclaration(classDecl, ctx);
				// record 는 equals 가 이미 있고, enum, interface 는 대상이 아니다
				JavaType.FullyQualified type = visited.getType();
				if (type == null) {
					return visited;
				}
				if (visited.getKind() != J.ClassDeclaration.Kind.Type.Class
						|| !targets(acc).contains(type.getFullyQualifiedName())) {
					return visited;
				}
				// 부모가 있으면 @EqualsAndHashCode 가 부모 필드를 비교하지 않아 UPDATE 가 빠질 수 있다
				if (visited.getExtends() == null && !hasEquals(visited) && usesLombok()) {
					maybeAddImport("lombok.EqualsAndHashCode");
					visited = JavaTemplate.builder("@EqualsAndHashCode")
						.imports("lombok.EqualsAndHashCode")
						.javaParser(JavaParser.fromJavaVersion()
							.dependsOn("package lombok; public @interface EqualsAndHashCode {}"))
						.build()
						.apply(updateCursor(visited), visited.getCoordinates()
							.addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
				}
				if (!TypeUtils.isAssignableTo(SERIALIZABLE, visited.getType())) {
					maybeAddImport(SERIALIZABLE);
					visited = (J.ClassDeclaration) new ImplementInterface<ExecutionContext>(visited, SERIALIZABLE)
						.visitNonNull(visited, ctx, getCursor().getParentOrThrow());
				}
				return visited;
			}

			private boolean usesLombok() {
				return getCursor().firstEnclosingOrThrow(J.CompilationUnit.class)
					.getImports()
					.stream()
					.anyMatch((imported) -> imported.getPackageName().equals("lombok"));
			}

			private boolean hasEquals(J.ClassDeclaration classDecl) {
				boolean lombokEquals = classDecl.getLeadingAnnotations()
					.stream()
					.anyMatch((annotation) -> LOMBOK_EQUALS.stream()
						.anyMatch((lombok) -> TypeUtils.isOfClassType(annotation.getType(), lombok)));
				return lombokEquals || classDecl.getBody()
					.getStatements()
					.stream()
					.filter(J.MethodDeclaration.class::isInstance)
					.map(J.MethodDeclaration.class::cast)
					.anyMatch((method) -> "equals".equals(method.getSimpleName()));
			}
		};
	}

	private static final List<String> LOMBOK_EQUALS = Arrays.asList("lombok.EqualsAndHashCode", "lombok.Data",
			"lombok.Value");

	private static Set<String> targets(Accumulator acc) {
		if (acc.targets == null) {
			acc.targets = reachable(acc, acc.roots);
		}
		return acc.targets;
	}

	private static Set<String> reachable(Accumulator acc, Set<String> roots) {
		Set<String> result = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>(roots);
		while (!queue.isEmpty()) {
			String className = queue.pop();
			if (acc.fieldTypesByClass.containsKey(className) && result.add(className)) {
				queue.addAll(acc.fieldTypesByClass.get(className));
			}
		}
		return result;
	}

	private static boolean isJsonTypeAnnotation(J.Annotation annotation) {
		if (annotation.getArguments() == null) {
			return false;
		}
		if (TypeUtils.isOfClassType(annotation.getType(), "org.hibernate.annotations.JdbcTypeCode")) {
			return annotation.getArguments()
				.stream()
				.anyMatch((arg) -> valueOf(arg) instanceof J.FieldAccess field && "JSON".equals(field.getSimpleName()));
		}
		if (!TypeUtils.isOfClassType(annotation.getType(), HIBERNATE_TYPE)) {
			return false;
		}
		for (Expression arg : annotation.getArguments()) {
			if (valueOf(arg) instanceof J.FieldAccess classLiteral
					&& classLiteral.getTarget().getType() instanceof JavaType.FullyQualified type) {
				String typeName = type.getFullyQualifiedName();
				if ((typeName.startsWith("io.hypersistence.") || typeName.startsWith("com.vladmihalcea."))
						&& typeName.contains("Json")) {
					return true;
				}
			}
		}
		return false;
	}

	private static J valueOf(Expression arg) {
		return (arg instanceof J.Assignment assignment) ? assignment.getAssignment() : arg;
	}

	/** List&lt;Item&gt;, Item[] 처럼 감싼 타입도 안쪽 클래스까지 모은다 */
	private static void collectTypes(@Nullable JavaType type, Set<String> into) {
		if (type instanceof JavaType.Parameterized parameterized) {
			parameterized.getTypeParameters().forEach((typeParameter) -> collectTypes(typeParameter, into));
			collectTypes(parameterized.getType(), into);
		}
		else if (type instanceof JavaType.Array array) {
			collectTypes(array.getElemType(), into);
		}
		else if (type instanceof JavaType.FullyQualified fullyQualified) {
			into.add(fullyQualified.getFullyQualifiedName());
		}
	}

	public static class Accumulator {

		final Map<String, Set<String>> fieldTypesByClass = new HashMap<>();

		final Set<String> roots = new HashSet<>();

		@Nullable Set<String> targets;

	}

}
