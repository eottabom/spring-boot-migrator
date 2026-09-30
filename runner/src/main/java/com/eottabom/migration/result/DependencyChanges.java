package com.eottabom.migration.result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.eottabom.migration.io.TextFiles;

/**
 * stage 전후 resolve 된 의존성 버전 비교.
 *
 * @param changed 버전이 바뀐 것 (major, minor, patch 순, 같은 구분은 이름순)
 */
public record DependencyChanges(List<VersionChange> changed, List<String> added, List<String> removed) {

	private static final List<String> LEVEL_ORDER = List.of("major", "minor", "patch");

	static DependencyChanges compare(Map<String, String> before, Map<String, String> after) {
		if (before.isEmpty() || after.isEmpty()) {
			return new DependencyChanges(List.of(), List.of(), List.of());
		}
		List<VersionChange> changed = new ArrayList<>();
		after.forEach((name, version) -> {
			String previous = before.get(name);
			if (previous != null && !previous.equals(version)) {
				changed.add(new VersionChange(name, previous, version, level(previous, version)));
			}
		});
		changed.sort(Comparator.comparingInt((VersionChange c) -> LEVEL_ORDER.indexOf(c.level()))
			.thenComparing(VersionChange::name));
		List<String> added = new ArrayList<>(new TreeSet<>(after.keySet()));
		added.removeAll(before.keySet());
		List<String> removed = new ArrayList<>(new TreeSet<>(before.keySet()));
		removed.removeAll(after.keySet());
		return new DependencyChanges(changed, added, removed);
	}

	boolean isEmpty() {
		return this.changed.isEmpty() && this.added.isEmpty() && this.removed.isEmpty();
	}

	static String level(String before, String after) {
		String[] x = before.split("[.-]");
		String[] y = after.split("[.-]");
		if (!x[0].equals(y[0])) {
			return "major";
		}
		return (x.length > 1 && y.length > 1 && !x[1].equals(y[1])) ? "minor" : "patch";
	}

	public static Map<String, String> readVersions(Path file) {
		Map<String, String> versions = new LinkedHashMap<>();
		for (String line : TextFiles.readLines(file)) {
			int eq = line.indexOf('=');
			if (eq > 0) {
				versions.put(line.substring(0, eq), line.substring(eq + 1));
			}
		}
		return versions;
	}

	/**
	 * @param level major | minor | patch
	 */
	record VersionChange(String name, String before, String after, String level) {

		boolean isPatch() {
			return "patch".equals(this.level);
		}

	}

}
