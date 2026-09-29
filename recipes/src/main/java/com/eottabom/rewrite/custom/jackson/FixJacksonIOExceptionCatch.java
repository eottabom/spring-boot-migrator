package com.eottabom.rewrite.custom.jackson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.NameTree;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

/**
 * Jackson 3 전환이 {@code catch (IOException e)} 를 {@code catch (JacksonException e)} 로 바꾼
 * 뒤의 컴파일 에러를 고친다. try 본문이 IOException 을 던지면 multi-catch 에 IOException 을 더하고, 던지지 않으면 뺀다.
 */
public class FixJacksonIOExceptionCatch extends Recipe {

	private static final String JACKSON_EXCEPTION = "tools.jackson.core.JacksonException";

	private static final String IO_EXCEPTION = "java.io.IOException";

	@Override
	public String getDisplayName() {
		return "Jackson 3 예외 처리 절의 IOException 정리";
	}

	@Override
	public String getDescription() {
		return "IOException 을 던지는 try 본문이면 catch (JacksonException | IOException e) 로 보완하고, 아니면 multi-catch 에서 IOException 을 뺀다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>(JACKSON_EXCEPTION, false), new JavaIsoVisitor<>() {
			@Override
			public J.Try visitTry(J.Try tryable, ExecutionContext ctx) {
				J.Try tryStatement = super.visitTry(tryable, ctx);
				if (!throwsIOException(tryStatement)) {
					return removeIOExceptionFromJacksonMultiCatch(tryStatement);
				}
				return catchesIOException(tryStatement) ? tryStatement : addIOExceptionToJacksonCatch(tryStatement);
			}

			/** 첫 번째 JacksonException catch 에 IOException 을 더한다 */
			private J.Try addIOExceptionToJacksonCatch(J.Try tryStatement) {
				var catches = new ArrayList<>(tryStatement.getCatches());
				for (int i = 0; i < catches.size(); i++) {
					J.Try.Catch catchClause = catches.get(i);
					J.VariableDeclarations parameter = catchClause.getParameter().getTree();
					if (parameter.getTypeExpression() instanceof J.MultiCatch
							|| !TypeUtils.isOfClassType(parameter.getType(), JACKSON_EXCEPTION)) {
						continue;
					}
					var ioException = new J.Identifier(Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY,
							new ArrayList<>(), "IOException", JavaType.ShallowClass.build(IO_EXCEPTION), null);
					TypeTree typeExpression = Objects.requireNonNull(parameter.getTypeExpression());
					List<JRightPadded<NameTree>> alternatives = List
						.of(JRightPadded.<NameTree>build((NameTree) typeExpression.withPrefix(Space.EMPTY))
							.withAfter(Space.SINGLE_SPACE), JRightPadded.<NameTree>build(ioException));
					var multiCatch = new J.MultiCatch(Tree.randomId(), typeExpression.getPrefix(), Markers.EMPTY,
							alternatives);
					catches.set(i, withParameter(catchClause, parameter.withTypeExpression(multiCatch)));
					maybeAddImport(IO_EXCEPTION);
					return tryStatement.withCatches(catches);
				}
				return tryStatement;
			}

			private J.Try removeIOExceptionFromJacksonMultiCatch(J.Try tryStatement) {
				var catches = new ArrayList<>(tryStatement.getCatches());
				boolean changed = false;
				for (int i = 0; i < catches.size(); i++) {
					J.Try.Catch catchClause = catches.get(i);
					J.VariableDeclarations parameter = catchClause.getParameter().getTree();
					if (!(parameter.getTypeExpression() instanceof J.MultiCatch multiCatch)
							|| !catchesJackson(multiCatch)) {
						continue;
					}
					List<NameTree> withoutIO = multiCatch.getAlternatives()
						.stream()
						.filter((alternative) -> !TypeUtils.isOfClassType(alternative.getType(), IO_EXCEPTION))
						.toList();
					if (withoutIO.size() == multiCatch.getAlternatives().size()) {
						continue;
					}
					TypeTree typeExpression = (withoutIO.size() == 1)
							? (TypeTree) withoutIO.get(0).withPrefix(multiCatch.getPrefix())
							: multiCatch.getPadding().withAlternatives(separatedByPipes(withoutIO));
					catches.set(i, withParameter(catchClause, parameter.withTypeExpression(typeExpression)));
					changed = true;
				}
				if (!changed) {
					return tryStatement;
				}
				maybeRemoveImport(IO_EXCEPTION);
				return tryStatement.withCatches(catches);
			}

			private boolean catchesJackson(J.MultiCatch multiCatch) {
				return multiCatch.getAlternatives()
					.stream()
					.anyMatch((alternative) -> TypeUtils.isOfClassType(alternative.getType(), JACKSON_EXCEPTION));
			}

			private List<JRightPadded<NameTree>> separatedByPipes(List<NameTree> types) {
				List<JRightPadded<NameTree>> padded = new ArrayList<>();
				for (int i = 0; i < types.size(); i++) {
					NameTree type = types.get(i).withPrefix((i == 0) ? Space.EMPTY : Space.SINGLE_SPACE);
					JRightPadded<NameTree> element = JRightPadded.build(type);
					padded.add((i < types.size() - 1) ? element.withAfter(Space.SINGLE_SPACE) : element);
				}
				return padded;
			}

			private J.Try.Catch withParameter(J.Try.Catch catchClause, J.VariableDeclarations parameter) {
				return catchClause.withParameter(catchClause.getParameter().withTree(parameter));
			}

			/** IOException 이나 그 상위 타입을 받는 catch 가 있다 */
			private boolean catchesIOException(J.Try tryStatement) {
				for (J.Try.Catch catchClause : tryStatement.getCatches()) {
					JavaType caught = catchClause.getParameter().getTree().getType();
					List<JavaType> caughtTypes = (caught instanceof JavaType.MultiCatch multiCatch)
							? multiCatch.getThrowableTypes() : List.of(caught);
					for (JavaType caughtType : caughtTypes) {
						if (TypeUtils.isAssignableTo(caughtType, JavaType.ShallowClass.build(IO_EXCEPTION))) {
							return true;
						}
					}
				}
				return false;
			}

			/** 선언 타입의 close()를 우선하고, 재정의가 없을 때만 상위 타입을 확인한다. */
			private List<JavaType.Method> closeMethods(JavaType.@Nullable FullyQualified type, Set<String> visited) {
				if (type == null || !visited.add(type.getFullyQualifiedName())) {
					return List.of();
				}
				List<JavaType.Method> declared = type.getMethods()
					.stream()
					.filter((method) -> "close".equals(method.getName()) && method.getParameterTypes().isEmpty())
					.toList();
				if (!declared.isEmpty()) {
					return declared;
				}
				List<JavaType.Method> inherited = closeMethods(type.getSupertype(), visited);
				if (!inherited.isEmpty()) {
					return inherited;
				}
				return type.getInterfaces()
					.stream()
					.flatMap((parent) -> closeMethods(parent, visited).stream())
					.toList();
			}

			private boolean throwsIOException(J.Try tryStatement) {
				var found = new AtomicBoolean();
				JavaIsoVisitor<AtomicBoolean> visitor = new JavaIsoVisitor<>() {
					@Override
					public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, AtomicBoolean throwsIO) {
						check(method.getMethodType(), throwsIO);
						return super.visitMethodInvocation(method, throwsIO);
					}

					@Override
					public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean throwsIO) {
						check(newClass.getConstructorType(), throwsIO);
						// 익명 클래스 본문의 호출은 그 메서드 안에서 던져진다 (생성자 인자만 본다)
						super.visitNewClass(newClass.withBody(null), throwsIO);
						return newClass;
					}

					@Override
					public J.Lambda visitLambda(J.Lambda lambda, AtomicBoolean throwsIO) {
						return lambda;
					}

					@Override
					public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
							AtomicBoolean throwsIO) {
						// 로컬 클래스 본문도 이 try 에서 실행되지 않는다
						return classDecl;
					}

					@Override
					public J.Throw visitThrow(J.Throw thrown, AtomicBoolean throwsIO) {
						if (TypeUtils.isAssignableTo(IO_EXCEPTION, thrown.getException().getType())) {
							throwsIO.set(true);
						}
						return super.visitThrow(thrown, throwsIO);
					}

					@Override
					public J.Try visitTry(J.Try nested, AtomicBoolean throwsIO) {
						if (!catchesIOException(nested)) {
							return super.visitTry(nested, throwsIO);
						}
						// 안쪽 try 가 받는 IOException 은 바깥으로 나오지 않는다. catch 와 finally 에서 던지는
						// 것만 본다
						nested.getCatches().forEach((catchClause) -> visit(catchClause.getBody(), throwsIO));
						if (nested.getFinally() != null) {
							visit(nested.getFinally(), throwsIO);
						}
						return nested;
					}

					private void check(JavaType.@Nullable Method methodType, AtomicBoolean throwsIO) {
						// upstream 은 타입 이름만 바꾸고 Jackson 2 의 throws 정보는 남기므로 Jackson 3
						// 메서드는 무시한다
						if (methodType == null
								|| methodType.getDeclaringType().getFullyQualifiedName().startsWith("tools.jackson.")) {
							return;
						}
						if (methodType.getThrownExceptions()
							.stream()
							.anyMatch((thrown) -> TypeUtils.isAssignableTo(IO_EXCEPTION, thrown))) {
							throwsIO.set(true);
						}
					}
				};
				visitor.visit(tryStatement.getBody(), found);
				if (tryStatement.getResources() != null) {
					for (J.Try.Resource resource : tryStatement.getResources()) {
						visitor.visit(resource, found);
						JavaType.FullyQualified type = TypeUtils
							.asFullyQualified(resource.getVariableDeclarations().getType());
						if (closeMethods(type, new HashSet<>()).stream()
							.anyMatch((method) -> method.getThrownExceptions()
								.stream()
								.anyMatch((thrown) -> TypeUtils.isAssignableTo(IO_EXCEPTION, thrown)))) {
							found.set(true);
						}
					}
				}
				return found.get();
			}
		});
	}

}
