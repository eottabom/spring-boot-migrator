package com.eottabom.rewrite.custom.gradle;

import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.FindSourceFiles;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Statement;

/**
 * settings 스크립트에 잘못 들어간 최상위 {@code dependencies { }} 블록을 지운다. upstream
 * UpgradeDependencyVersion 이 중첩 빌드의 settings 스크립트를 빌드 파일로 보고 넣는 경우가 있다. buildscript,
 * pluginManagement 안은 건드리지 않는다.
 */
public class RemoveDependenciesFromSettingsScript extends Recipe {

	@Override
	public String getDisplayName() {
		return "Settings 스크립트의 최상위 의존성 블록 제거";
	}

	@Override
	public String getDescription() {
		return "Settings 스크립트(settings.gradle, settings.gradle.kts) 에 잘못 들어간 최상위 dependencies { } 블록을 지운다. 중첩 빌드의 settings 스크립트에 upstream 의존성 레시피가 넣는 문제 보정.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new FindSourceFiles("**/settings.gradle{,.kts}").getVisitor(),
				new JavaIsoVisitor<>() {
					@Override
					public J preVisit(J tree, ExecutionContext ctx) {
						// Groovy 는 최상위 문장이 컴파일 단위에, Kotlin 은 그 아래 블록에 있다
						if (tree instanceof G.CompilationUnit script) {
							return script.withStatements(withoutDependencies(script.getStatements()));
						}
						return tree;
					}

					@Override
					public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
						J.Block visited = super.visitBlock(block, ctx);
						if (getCursor().getParentTreeCursor().getValue() instanceof JavaSourceFile) {
							return visited.withStatements(withoutDependencies(visited.getStatements()));
						}
						return visited;
					}

					/** 지울 것이 없으면 같은 목록을 돌려줘 변경으로 보지 않게 한다 */
					private List<Statement> withoutDependencies(List<Statement> statements) {
						List<Statement> kept = statements.stream()
							.filter((statement) -> !isDependencies(statement))
							.toList();
						return (kept.size() == statements.size()) ? statements : kept;
					}

					private boolean isDependencies(Statement statement) {
						return statement instanceof J.MethodInvocation invocation
								&& "dependencies".equals(invocation.getSimpleName());
					}
				});
	}

}
