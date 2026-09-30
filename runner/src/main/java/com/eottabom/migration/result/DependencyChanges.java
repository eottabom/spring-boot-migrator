package com.eottabom.migration.result;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import com.eottabom.migration.version.ResolvedVersions;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * stage 전후 resolve 된 의존성 버전 비교.
 *
 * @param changed 버전이 바뀐 것 (major, minor, patch 순, 같은 구분은 이름순)
 */
public record DependencyChanges(List<VersionChange> changed, List<String> added, List<String> removed) {

	static DependencyChanges compare(ResolvedVersions beforeVersions, ResolvedVersions afterVersions) {
		Map<String, String> before = beforeVersions.byModule();
		Map<String, String> after = afterVersions.byModule();
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
		changed.sort(Comparator.comparing(VersionChange::level).thenComparing(VersionChange::name));
		List<String> added = new ArrayList<>(new TreeSet<>(after.keySet()));
		added.removeAll(before.keySet());
		List<String> removed = new ArrayList<>(new TreeSet<>(before.keySet()));
		removed.removeAll(after.keySet());
		return new DependencyChanges(changed, added, removed);
	}

	boolean isEmpty() {
		return this.changed.isEmpty() && this.added.isEmpty() && this.removed.isEmpty();
	}

	static Level level(String before, String after) {
		String[] beforeParts = before.split("[.-]");
		String[] afterParts = after.split("[.-]");
		if (!beforeParts[0].equals(afterParts[0])) {
			return Level.MAJOR;
		}
		boolean minorChanged = beforeParts.length > 1 && afterParts.length > 1 && !beforeParts[1].equals(afterParts[1]);
		return minorChanged ? Level.MINOR : Level.PATCH;
	}

	record VersionChange(String name, String before, String after, Level level) {

		boolean isPatch() {
			return this.level == Level.PATCH;
		}

	}

	/** 버전의 어느 자리가 바뀌었는가. 선언 순서가 결과의 정렬 순서다 */
	enum Level {

		@JsonProperty("major")
		MAJOR,

		@JsonProperty("minor")
		MINOR,

		@JsonProperty("patch")
		PATCH;

		String label() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

}
