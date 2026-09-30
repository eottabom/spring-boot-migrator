package com.eottabom.rewrite.custom.gradle;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.DependencyVersionSelector;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.gradle.marker.GradleSettings;
import org.openrewrite.maven.MavenDownloadingException;
import org.openrewrite.maven.table.MavenMetadataFailures;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.semver.ExactVersion;
import org.openrewrite.semver.LatestRelease;
import org.openrewrite.semver.Semver;

/**
 * upstream 이 빌드 스크립트에만 적용하는 의존성, 플러그인 버전 변경을 {@code gradle/*.versions.toml} 에도 적용한다. 규칙은
 * upstream/catalog.yml 에서 오고, 버전은 upstream 과 같은 {@link DependencyVersionSelector} 로 고른다.
 */
public final class UpgradeVersionCatalog extends ScanningRecipe<UpgradeVersionCatalog.Accumulator> {

	@Option(displayName = "Rules", description = "dependency / plugin / change 규칙. 형식은 VersionCatalogEditor.Rule",
			example = "plugin org.springframework.boot 3.4.x")
	private final List<String> rules;

	private final transient MavenMetadataFailures metadataFailures = new MavenMetadataFailures(this);

	public UpgradeVersionCatalog(List<String> rules) {
		this.rules = rules;
	}

	@Override
	public String getDisplayName() {
		return "Gradle Version Catalog 버전 정렬";
	}

	@Override
	public String getDescription() {
		return "빌드 스크립트에 upstream 이 적용하는 의존성/플러그인 버전 변경을 gradle/*.versions.toml 에도 적용한다.";
	}

	@Override
	public Accumulator getInitialValue(ExecutionContext ctx) {
		return new Accumulator();
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
		return new TreeVisitor<>() {
			@Override
			public Tree preVisit(Tree tree, ExecutionContext ctx) {
				stopAfterPreVisit();
				SourceFile source = (SourceFile) tree;
				source.getMarkers().findFirst(GradleProject.class).ifPresent((project) -> {
					acc.projects.putIfAbsent(source.getSourcePath(), project);
					project.getPlugins().forEach((plugin) -> acc.plugins.add(plugin.getId()));
					project.getConfigurations()
						.forEach((configuration) -> configuration.getResolved()
							.forEach((dependency) -> acc.dependencies
								.add(dependency.getGroupId() + ":" + dependency.getArtifactId())));
				});
				source.getMarkers().findFirst(GradleSettings.class).ifPresent((settings) -> acc.settings = settings);
				return tree;
			}
		};
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
		List<VersionCatalogEditor.Rule> parsed = this.rules.stream().map(VersionCatalogEditor.Rule::parse).toList();
		return new TreeVisitor<>() {
			@Override
			public Tree preVisit(Tree tree, ExecutionContext ctx) {
				stopAfterPreVisit();
				SourceFile source = (SourceFile) tree;
				if (!VersionCatalogSource.isCatalog(source)) {
					return tree;
				}
				return VersionCatalogSource.withText(source, VersionCatalogEditor.apply(source.printAll(), parsed,
						resolver(acc, ctx), new VersionCatalogEditor.ProjectFacts(acc.plugins, acc.dependencies)), ctx);
			}
		};
	}

	private VersionCatalogEditor.Resolver resolver(Accumulator acc, ExecutionContext ctx) {
		return (group, artifact, current, newVersion, versionPattern, plugin) -> {
			// Semver.isVersion 은 3.4.x 같은 패턴도 true 라서 쓰지 않는다
			if (Semver.validate(newVersion, versionPattern).getValue() instanceof ExactVersion) {
				return (current == null || isNewer(current, newVersion)) ? newVersion : null;
			}
			GradleProject root = acc.rootProject();
			if (root == null) {
				return null;
			}
			DependencyVersionSelector selector = new DependencyVersionSelector(this.metadataFailures, root,
					acc.settings);
			String configuration = plugin ? "classpath" : null;
			try {
				String selected = (current != null)
						? selector.select(new GroupArtifactVersion(group, artifact, current), configuration, newVersion,
								versionPattern, ctx)
						: selector.select(new GroupArtifact(group, artifact), configuration, newVersion, versionPattern,
								ctx);
				// DependencyVersionSelector 는 current 보다 새 버전만 고른다
				return selected;
			}
			catch (MavenDownloadingException ex) {
				return null;
			}
		};
	}

	private static boolean isNewer(String current, String candidate) {
		return new LatestRelease(null).compare(null, current, candidate) < 0;
	}

	public static class Accumulator {

		/** 빌드 파일 경로를 깊이와 이름으로 정렬한 모델. 가장 얕은 것이 루트 프로젝트다 */
		final Map<java.nio.file.Path, GradleProject> projects = new TreeMap<>(
				Comparator.comparingInt(java.nio.file.Path::getNameCount).thenComparing(java.nio.file.Path::toString));

		@Nullable GradleSettings settings;

		final Set<String> plugins = new HashSet<>();

		final Set<String> dependencies = new HashSet<>();

		/** 루트 프로젝트의 저장소로 버전을 고른다 */
		@Nullable GradleProject rootProject() {
			return this.projects.isEmpty() ? null : this.projects.entrySet().iterator().next().getValue();
		}

	}

}
