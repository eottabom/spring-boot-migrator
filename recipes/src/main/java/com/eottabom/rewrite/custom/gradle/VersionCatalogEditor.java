package com.eottabom.rewrite.custom.gradle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.openrewrite.internal.StringUtils;

/** version catalog 텍스트에 버전 규칙을 적용한다. 한 줄에 한 항목인 형태만 다루고 주석과 정렬은 그대로 둔다. */
final class VersionCatalogEditor {

	private static final Pattern KEY_VALUE = Pattern.compile("^\\s*\"?([A-Za-z0-9_.\\-]+)\"?\\s*=\\s*(.*)$");

	private static final Pattern SECTION = Pattern.compile("^\\s*\\[([A-Za-z0-9_.\\-]+)]\\s*(#.*)?$");

	private static final Pattern STRING = Pattern.compile("^(['\"])(.*?)\\1");

	/** inline table 의 key = "value". version = { ref = "x" } 는 version.ref 로 본다 */
	private static final Pattern ATTRIBUTE = Pattern
		.compile("([\\w.]+)\\s*=\\s*(?:(['\"])(.*?)\\2|\\{\\s*ref\\s*=\\s*(['\"])(.*?)\\4\\s*})");

	private final List<String> lines;

	private final Resolver resolver;

	private int edits;

	private VersionCatalogEditor(String toml, Resolver resolver) {
		this.lines = new ArrayList<>(Arrays.asList(toml.split("\n", -1)));
		this.resolver = resolver;
	}

	/**
	 * @param facts 대상 프로젝트의 플러그인과 의존성. 조건이 붙은 규칙은 맞을 때만 적용한다
	 */
	static String apply(String toml, List<Rule> rules, Resolver resolver, ProjectFacts facts) {
		VersionCatalogEditor editor = new VersionCatalogEditor(toml, resolver);
		for (Rule rule : rules) {
			if (rule.conditions().stream().allMatch(facts::satisfies)) {
				editor.apply(rule);
			}
		}
		return String.join("\n", editor.lines);
	}

	private void apply(Rule rule) {
		Map<String, String> resolvedKeys = new HashMap<>();
		Catalog catalog = parse(this.lines);
		int parsedAt = this.edits;
		// 항목 수는 그대로라 고친 뒤에만 다시 읽는다
		for (int i = 0; i < catalog.entries().size(); i++) {
			if (parsedAt != this.edits) {
				catalog = parse(this.lines);
				parsedAt = this.edits;
			}
			Entry entry = catalog.entries().get(i);
			if (!rule.matches(entry)) {
				continue;
			}
			if (rule.kind() == Rule.Kind.CHANGE) {
				change(catalog, entry, rule);
				continue;
			}
			String current = catalog.versionOf(entry);
			if (current == null) {
				continue;
			}
			String target = (entry.ref() != null)
					? resolvedKeys.computeIfAbsent(entry.ref(), (ref) -> resolve(entry, current, rule))
					: resolve(entry, current, rule);
			if (target == null) {
				continue;
			}
			if (entry.ref() != null) {
				setVersionKey(catalog, entry.ref(), target);
			}
			else {
				setInlineVersion(entry, target);
			}
		}
	}

	private @Nullable String resolve(Entry entry, String current, Rule rule) {
		return this.resolver.resolve(entry.group(), entry.artifact(), current,
				Objects.requireNonNull(rule.newVersion()), rule.versionPattern(), entry.plugin());
	}

	private void change(Catalog catalog, Entry entry, Rule rule) {
		String newGroup = Objects.requireNonNull(rule.newGroup());
		String newArtifact = Objects.requireNonNull(rule.newArtifact());
		String group = "*".equals(newGroup) ? entry.group() : newGroup;
		String artifact = "*".equals(newArtifact) ? entry.artifact() : newArtifact;
		String current = catalog.versionOf(entry);
		String newVersion = rule.newVersion();
		String target = null;
		if (current != null && newVersion != null) {
			target = this.resolver.resolve(group, artifact, null, newVersion, rule.versionPattern(), false);
			// 좌표만 바꾸고 버전을 못 올리면 새 좌표에 없는 버전이 남는다
			if (target == null) {
				return;
			}
		}
		setCoordinates(entry, group, artifact);
		if (target == null || target.equals(current)) {
			return;
		}
		if (entry.ref() == null) {
			setInlineVersion(new Entry(entry.line(), entry.alias(), entry.plugin(), entry.notation(), group, artifact,
					entry.version(), entry.ref()), target);
		}
		else if (catalog.refUsers(entry.ref()).stream().allMatch(rule::matches)) {
			setVersionKey(catalog, entry.ref(), target);
		}
		else {
			// 같은 버전 키를 쓰는 다른 항목(ex. jackson-annotations)은 이전 버전에 남아야 한다
			String key = catalog.newKey(entry.alias());
			int at = Objects.requireNonNull(catalog.versions().get(entry.ref())) + 1;
			this.edit().add(at, key + " = \"" + target + "\"");
			int line = (at <= entry.line()) ? entry.line() + 1 : entry.line();
			this.edit().set(line, replaceAttribute(this.lines.get(line), "version.ref", key));
		}
	}

	private void setVersionKey(Catalog catalog, String key, String version) {
		int line = Objects.requireNonNull(catalog.versions().get(key));
		String text = this.lines.get(line);
		Matcher keyValue = KEY_VALUE.matcher(text);
		keyValue.matches();
		int start = keyValue.start(2);
		String value = text.substring(start);
		Matcher quoted = STRING.matcher(value);
		if (quoted.find()) {
			String replacement = quoted.group(1) + version + quoted.group(1);
			this.edit()
				.set(line, text.substring(0, start) + value.substring(0, quoted.start()) + replacement
						+ value.substring(quoted.end()));
		}
	}

	private void setInlineVersion(Entry entry, String version) {
		String text = this.lines.get(entry.line());
		if (entry.notation()) {
			this.edit()
				.set(entry.line(),
						replaceNotation(text, entry.group() + ":" + entry.artifact(), version, entry.plugin()));
		}
		else {
			this.edit().set(entry.line(), replaceAttribute(text, "version", version));
		}
	}

	private void setCoordinates(Entry entry, String group, String artifact) {
		String text = this.lines.get(entry.line());
		if (entry.notation()) {
			text = replaceNotation(text, group + ":" + artifact, entry.version(), false);
		}
		else if (attribute(text, "module") != null) {
			text = replaceAttribute(text, "module", group + ":" + artifact);
		}
		else {
			text = replaceAttribute(replaceAttribute(text, "group", group), "name", artifact);
		}
		this.edit().set(entry.line(), text);
	}

	/** 축약 좌표의 문자열 내용만 바꾸고 따옴표와 뒤의 주석은 보존한다. */
	private static String replaceNotation(String text, String coordinates, @Nullable String version, boolean plugin) {
		Matcher keyValue = KEY_VALUE.matcher(text);
		if (!keyValue.matches()) {
			return text;
		}
		Matcher quoted = STRING.matcher(keyValue.group(2));
		if (!quoted.find()) {
			return text;
		}
		String name = plugin ? coordinates.substring(0, coordinates.indexOf(':')) : coordinates;
		String value = name + ((version != null) ? ":" + version : "");
		int start = keyValue.start(2) + quoted.start(2);
		int end = keyValue.start(2) + quoted.end(2);
		return text.substring(0, start) + value + text.substring(end);
	}

	/** key 속성의 값을 to 로 바꾼다 */
	private static String replaceAttribute(String text, String key, String to) {
		return attributes(text).stream()
			.filter((attribute) -> attribute.key().equals(key))
			.findFirst()
			.map((attribute) -> text.substring(0, attribute.start()) + to + text.substring(attribute.end()))
			.orElse(text);
	}

	private static @Nullable String attribute(String text, String key) {
		return attributes(text).stream()
			.filter((attribute) -> attribute.key().equals(key))
			.map(Attribute::value)
			.findFirst()
			.orElse(null);
	}

	private static List<Attribute> attributes(String text) {
		List<Attribute> attributes = new ArrayList<>();
		Matcher matcher = ATTRIBUTE.matcher(text);
		while (matcher.find()) {
			int value = (matcher.group(5) != null) ? 5 : 3;
			String key = (value == 5) ? matcher.group(1) + ".ref" : matcher.group(1);
			attributes.add(new Attribute(key, matcher.group(value), matcher.start(value), matcher.end(value)));
		}
		return attributes;
	}

	private List<String> edit() {
		this.edits++;
		return this.lines;
	}

	private static Catalog parse(List<String> lines) {
		Map<String, Integer> versions = new LinkedHashMap<>();
		Map<String, String> versionValues = new HashMap<>();
		List<Entry> entries = new ArrayList<>();
		String section = "";
		for (int i = 0; i < lines.size(); i++) {
			String text = lines.get(i);
			Matcher header = SECTION.matcher(text);
			if (header.matches()) {
				section = header.group(1);
				continue;
			}
			Matcher keyValue = KEY_VALUE.matcher(text);
			if (text.trim().startsWith("#") || !keyValue.matches()) {
				continue;
			}
			String key = keyValue.group(1);
			String value = keyValue.group(2);
			switch (section) {
				case "versions" -> {
					Matcher quoted = STRING.matcher(value);
					if (quoted.find()) {
						versions.put(key, i);
						versionValues.put(key, quoted.group(2));
					}
				}
				case "libraries" -> {
					Entry entry = library(i, key, value);
					if (entry != null) {
						entries.add(entry);
					}
				}
				case "plugins" -> {
					Entry entry = plugin(i, key, value);
					if (entry != null) {
						entries.add(entry);
					}
				}
				default -> {
				}
			}
		}
		return new Catalog(versions, versionValues, entries);
	}

	private static @Nullable Entry library(int line, String alias, String value) {
		Matcher quoted = STRING.matcher(value);
		if (quoted.find()) {
			String[] gav = quoted.group(2).split(":");
			if (gav.length < 2) {
				return null;
			}
			return new Entry(line, alias, false, true, gav[0], gav[1], (gav.length > 2) ? gav[2] : null, null);
		}
		if (!value.startsWith("{")) {
			return null;
		}
		String group;
		String artifact;
		String module = attribute(value, "module");
		if (module != null) {
			String[] ga = module.split(":");
			if (ga.length != 2) {
				return null;
			}
			group = ga[0];
			artifact = ga[1];
		}
		else {
			group = attribute(value, "group");
			artifact = attribute(value, "name");
			if (group == null || artifact == null) {
				return null;
			}
		}
		return new Entry(line, alias, false, false, group, artifact, attribute(value, "version"),
				attribute(value, "version.ref"));
	}

	private static @Nullable Entry plugin(int line, String alias, String value) {
		Matcher quoted = STRING.matcher(value);
		if (quoted.find()) {
			String[] parts = quoted.group(2).split(":");
			return new Entry(line, alias, true, true, parts[0], parts[0] + ".gradle.plugin",
					(parts.length > 1) ? parts[1] : null, null);
		}
		String id = attribute(value, "id");
		if (id == null) {
			return null;
		}
		return new Entry(line, alias, true, false, id, id + ".gradle.plugin", attribute(value, "version"),
				attribute(value, "version.ref"));
	}

	static Map<String, String> libraryAliases(String toml) {
		Map<String, String> aliases = new LinkedHashMap<>();
		parse(Arrays.asList(toml.split("\n", -1))).entries()
			.stream()
			.filter((entry) -> !entry.plugin())
			.forEach((entry) -> aliases.putIfAbsent(entry.group() + ":" + entry.artifact(), entry.alias()));
		return aliases;
	}

	/**
	 * [libraries] 끝에 항목을 더한다. 같은 모듈이 있으면 건너뛴다.
	 * @param libraries alias 와 좌표
	 */
	static String addLibraries(String toml, Map<String, String> libraries) {
		Map<String, String> existing = libraryAliases(toml);
		List<String> added = new ArrayList<>();
		libraries.forEach((alias, coordinates) -> {
			String[] gav = coordinates.split(":");
			if (!existing.containsKey(gav[0] + ":" + gav[1])) {
				added.add(alias + " = { module = \"" + gav[0] + ":" + gav[1] + "\""
						+ ((gav.length > 2) ? ", version = \"" + gav[2] + "\"" : "") + " }");
			}
		});
		if (added.isEmpty()) {
			return toml;
		}
		List<String> lines = new ArrayList<>(Arrays.asList(toml.split("\n", -1)));
		int section = -1;
		for (int i = 0; i < lines.size(); i++) {
			Matcher header = SECTION.matcher(lines.get(i));
			if (header.matches() && "libraries".equals(header.group(1))) {
				section = i;
			}
		}
		if (section < 0) {
			int end = lines.get(lines.size() - 1).isEmpty() ? lines.size() - 1 : lines.size();
			lines.addAll(end, List.of("", "[libraries]"));
			lines.addAll(end + 2, added);
			return String.join("\n", lines);
		}
		// 섹션의 마지막 항목 바로 뒤 (뒤따르는 빈 줄과 주석은 그대로 둔다)
		int last = section;
		for (int i = section + 1; i < lines.size() && !SECTION.matcher(lines.get(i)).matches(); i++) {
			if (KEY_VALUE.matcher(lines.get(i)).matches()) {
				last = i;
			}
		}
		lines.addAll(last + 1, added);
		return String.join("\n", lines);
	}

	/**
	 * upstream 버전 변경 레시피 하나를 catalog 에 옮긴 규칙. 형식은 upstream/catalog.yml 참고. 좌표는 glob 을 쓸 수
	 * 있고, 끝의 when-plugin, when-dependency, unless-dependency 는 적용 조건이다.
	 */
	record Rule(Kind kind, String group, @Nullable String artifact, @Nullable String newGroup,
			@Nullable String newArtifact, @Nullable String newVersion, @Nullable String versionPattern,
			List<Condition> conditions) {

		static Rule parse(String rule) {
			List<String> tokens = new ArrayList<>(Arrays.asList(rule.trim().split("\\s+")));
			List<Condition> conditions = new ArrayList<>();
			for (int i = tokens.size() - 2; i >= 1; i--) {
				Condition.Kind condition = Condition.Kind.of(tokens.get(i));
				if (condition != null) {
					conditions.add(0, new Condition(condition, tokens.get(i + 1)));
					tokens.subList(i, i + 2).clear();
				}
			}
			String[] t = tokens.toArray(String[]::new);
			Kind kind = Kind.valueOf(t[0].toUpperCase());
			return switch (kind) {
				case DEPENDENCY -> {
					String[] ga = t[1].split(":");
					yield new Rule(kind, ga[0], ga[1], null, null, t[2], arg(t, 3), conditions);
				}
				case PLUGIN -> new Rule(kind, t[1], null, null, null, t[2], arg(t, 3), conditions);
				case CHANGE -> {
					String[] ga = t[1].split(":");
					String[] target = t[2].split(":");
					yield new Rule(kind, ga[0], ga[1], target[0], target[1], arg(t, 3), arg(t, 4), conditions);
				}
			};
		}

		private static @Nullable String arg(String[] tokens, int index) {
			return (tokens.length > index) ? tokens[index] : null;
		}

		boolean matches(Entry entry) {
			if (this.kind == Kind.PLUGIN) {
				return entry.plugin() && StringUtils.matchesGlob(entry.group(), this.group);
			}
			return !entry.plugin() && StringUtils.matchesGlob(entry.group(), this.group)
					&& StringUtils.matchesGlob(entry.artifact(), this.artifact);
		}

		enum Kind {

			DEPENDENCY, PLUGIN, CHANGE

		}

	}

	/**
	 * @param value plugin id 또는 group:artifact (glob)
	 */
	record Condition(Kind kind, String value) {

		enum Kind {

			PLUGIN("when-plugin"), DEPENDENCY("when-dependency"), NO_DEPENDENCY("unless-dependency");

			private final String keyword;

			Kind(String keyword) {
				this.keyword = keyword;
			}

			static @Nullable Kind of(String keyword) {
				for (Kind kind : values()) {
					if (kind.keyword.equals(keyword)) {
						return kind;
					}
				}
				return null;
			}

		}

	}

	/**
	 * 조건을 판단할 대상 프로젝트 정보
	 *
	 * @param dependencies resolve 된 group:artifact (transitive 포함)
	 */
	record ProjectFacts(Set<String> plugins, Set<String> dependencies) {

		boolean satisfies(Condition condition) {
			return switch (condition.kind()) {
				case PLUGIN -> this.plugins.contains(condition.value());
				case DEPENDENCY -> hasDependency(condition.value());
				case NO_DEPENDENCY -> !hasDependency(condition.value());
			};
		}

		private boolean hasDependency(String pattern) {
			return this.dependencies.stream().anyMatch((dependency) -> StringUtils.matchesGlob(dependency, pattern));
		}

	}

	/** 올릴 버전이 없으면 null */
	@FunctionalInterface
	interface Resolver {

		/**
		 * @param currentVersion null 이면 현재 버전과 비교하지 않는다 (좌표가 바뀌는 경우)
		 * @param plugin group 이 plugin id, artifact 가 plugin marker 이다
		 */
		@Nullable String resolve(String group, String artifact, @Nullable String currentVersion, String newVersion,
				@Nullable String versionPattern, boolean plugin);

	}

	/** 값과 그 값의 위치 (start, end) */
	private record Attribute(String key, String value, int start, int end) {
	}

	private record Entry(int line, String alias, boolean plugin, boolean notation, String group, String artifact,
			@Nullable String version, @Nullable String ref) {
	}

	/**
	 * @param versions [versions] 키와 줄 번호
	 */
	private record Catalog(Map<String, Integer> versions, Map<String, String> versionValues, List<Entry> entries) {

		@Nullable String versionOf(Entry entry) {
			return (entry.ref() != null) ? this.versionValues.get(entry.ref()) : entry.version();
		}

		List<Entry> refUsers(String ref) {
			return this.entries.stream().filter((entry) -> ref.equals(entry.ref())).toList();
		}

		String newKey(String alias) {
			String key = alias;
			for (int i = 2; this.versions.containsKey(key); i++) {
				key = alias + "-" + i;
			}
			return key;
		}

	}

}
