package com.eottabom.rewrite.custom.gradle;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.text.PlainText;
import org.openrewrite.toml.TomlParser;
import org.openrewrite.toml.tree.Toml;

/** gradle/*.versions.toml. 플러그인 버전에 따라 Toml 이나 PlainText 로 파싱되므로 텍스트로 다룬다. */
final class VersionCatalogSource {

	private VersionCatalogSource() {
	}

	static boolean isCatalog(SourceFile source) {
		Path path = source.getSourcePath();
		Path parent = path.getParent();
		return (source instanceof PlainText || source instanceof Toml.Document) && parent != null
				&& "gradle".equals(parent.getFileName().toString())
				&& path.getFileName().toString().endsWith(".versions.toml");
	}

	/**
	 * catalog 이름 (빌드 스크립트의 접근자 앞부분). gradle/deps.versions.toml 이면 deps, catalog 가 아니면
	 * null
	 */
	static @Nullable String catalogName(SourceFile source) {
		if (!isCatalog(source)) {
			return null;
		}
		String fileName = source.getSourcePath().getFileName().toString();
		return fileName.substring(0, fileName.length() - ".versions.toml".length());
	}

	/** 텍스트가 바뀌었으면 같은 id, 경로, 마커로 다시 만든다 */
	static SourceFile withText(SourceFile source, String text, ExecutionContext ctx) {
		if (text.equals(source.printAll())) {
			return source;
		}
		if (source instanceof PlainText plainText) {
			return plainText.withText(text);
		}
		// 다시 읽지 못하면 ParseError 가 오므로 원래 파일을 둔다
		return new TomlParser().parse(ctx, text)
			.findFirst()
			.filter(Toml.Document.class::isInstance)
			.<SourceFile>map((toml) -> toml.withSourcePath(source.getSourcePath())
				.withId(source.getId())
				.withMarkers(source.getMarkers()))
			.orElse(source);
	}

}
