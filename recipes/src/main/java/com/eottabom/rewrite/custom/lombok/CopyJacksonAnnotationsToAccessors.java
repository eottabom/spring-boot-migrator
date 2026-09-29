package com.eottabom.rewrite.custom.lombok;

import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextParser;
import org.openrewrite.text.PlainTextVisitor;

/**
 * 루트 lombok.config 에 {@code lombok.copyJacksonAnnotationsToAccessors = true} 를 넣는다.
 * Lombok 1.18.40 부터 필드의 Jackson 어노테이션을 getter 에 복사하지 않아 {@code isShow} 같은 필드가 JSON 에 두 번
 * 나간다. .gitignore 가 lombok.config 를 무시하면 루트 파일만 풀어 준다.
 */
public class CopyJacksonAnnotationsToAccessors extends ScanningRecipe<CopyJacksonAnnotationsToAccessors.Accumulator> {

	private static final String LOMBOK_CONFIG = "lombok.config";

	private static final String KEY = "lombok.copyJacksonAnnotationsToAccessors";

	private static final String ENTRY = KEY + " = true";

	private static final String GITIGNORE = ".gitignore";

	private static final String UNIGNORE = "!/lombok.config";

	private static final Pattern IGNORES_CONFIG = Pattern.compile("(?m)^\\s*(\\*\\*/|/)?lombok\\.config\\s*$");

	@Override
	public String getDisplayName() {
		return "Jackson 어노테이션을 접근자에 복사하는 Lombok 설정 추가";
	}

	@Override
	public String getDescription() {
		return "Lombok 1.18.40+ 에서 @JsonProperty 가 getter 에 복사되지 않아 JSON 속성이 중복되는 문제를 lombok.copyJacksonAnnotationsToAccessors = true 로 막는다.";
	}

	@Override
	public Accumulator getInitialValue(ExecutionContext ctx) {
		return new Accumulator();
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
		return new TreeVisitor<>() {
			@Override
			public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
				if (tree instanceof SourceFile source && LOMBOK_CONFIG.equals(source.getSourcePath().toString())) {
					acc.configExists = true;
				}
				else if (tree instanceof J.CompilationUnit cu && !acc.usesLombokWithJackson) {
					acc.usesLombokWithJackson = usesLombokWithJackson(cu);
				}
				return tree;
			}
		};
	}

	@Override
	public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
		if (!acc.usesLombokWithJackson || acc.configExists) {
			return Collections.emptyList();
		}
		return PlainTextParser.builder()
			.build()
			.parse(ENTRY + "\n")
			.map((config) -> config.<SourceFile>withSourcePath(Paths.get(LOMBOK_CONFIG)))
			.collect(Collectors.toList());
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
		return new PlainTextVisitor<>() {
			@Override
			public PlainText visitText(PlainText text, ExecutionContext ctx) {
				if (!acc.usesLombokWithJackson) {
					return text;
				}
				String path = text.getSourcePath().toString();
				if (GITIGNORE.equals(path)) {
					return unignoreRootConfig(text);
				}
				if (!LOMBOK_CONFIG.equals(path) || text.getText().contains(KEY)) {
					return text;
				}
				// 파일 끝 줄바꿈은 원래 파일을 따른다
				String content = text.getText();
				if (content.isEmpty()) {
					return text.withText(ENTRY + "\n");
				}
				return text.withText(content.endsWith("\n") ? content + ENTRY + "\n" : content + "\n" + ENTRY);
			}
		};
	}

	private static PlainText unignoreRootConfig(PlainText gitignore) {
		String content = gitignore.getText();
		if (!IGNORES_CONFIG.matcher(content).find() || content.contains(UNIGNORE)) {
			return gitignore;
		}
		String lines = "# 루트 lombok.config 는 커밋한다 (lombok.copyJacksonAnnotationsToAccessors)\n" + UNIGNORE;
		// 파일 끝 줄바꿈은 원래 파일을 따른다
		return gitignore.withText(content.endsWith("\n") ? content + lines + "\n" : content + "\n" + lines);
	}

	private static boolean usesLombokWithJackson(J.CompilationUnit cu) {
		boolean lombok = false;
		boolean jackson = false;
		for (JavaType type : cu.getTypesInUse().getTypesInUse()) {
			if (type instanceof JavaType.FullyQualified fullyQualified) {
				String fqn = fullyQualified.getFullyQualifiedName();
				lombok |= fqn.startsWith("lombok.");
				jackson |= fqn.startsWith("com.fasterxml.jackson.annotation.");
			}
		}
		return lombok && jackson;
	}

	public static class Accumulator {

		boolean usesLombokWithJackson;

		boolean configExists;

	}

}
