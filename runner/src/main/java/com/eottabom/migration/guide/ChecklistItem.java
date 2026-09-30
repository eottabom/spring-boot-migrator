package com.eottabom.migration.guide;

import java.util.List;
import java.util.regex.Pattern;

import com.eottabom.migration.version.Versions;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * stage 를 올린 뒤 확인할 항목.
 *
 * @param id 가이드 이름과 파일 안의 id (boot-3.4/mockbean)
 * @param recipe 고치는 레시피
 * @param detect 위치를 찾는 레시피 (코드를 바꾸지 않는다)
 * @param when stage 전후 classpath 에 이 의존성 중 하나라도 있을 때만 (조건이 없으면 null)
 * @param crosses 라이브러리 항목. 이 버전을 넘어갈 때 (이전 &lt; crosses &lt;= 이후)
 * @param affected 라이브러리 항목. [from, until) 에 이후 버전이 들어갈 때
 */
public record ChecklistItem(String id, String title, @Nullable String detail, @Nullable String source, Fix fix,
		@Nullable String recipe, @Nullable String detect, @Nullable When when, @Nullable String crosses,
		@Nullable VersionRange affected) {

	/** 조건이 없거나, 전후 의존성 중 하나라도 조건에 맞는다. 버전을 모르면(둘 다 비면) 조건이 있는 항목은 고르지 않는다 */
	boolean appliesTo(Iterable<String> dependencies) {
		if (this.when == null) {
			return true;
		}
		List<Pattern> patterns = this.when.dependencies().stream().map(ChecklistItem::glob).toList();
		for (String dependency : dependencies) {
			if (patterns.stream().anyMatch((pattern) -> pattern.matcher(dependency).matches())) {
				return true;
			}
		}
		return false;
	}

	/** 라이브러리 버전이 from 에서 to 로 바뀌며 이 항목의 버전 조건에 걸린다 */
	boolean triggeredBy(String from, String to) {
		if (from.equals(to)) {
			return false;
		}
		if (this.crosses != null) {
			return Versions.compare(from, this.crosses) < 0 && Versions.compare(to, this.crosses) >= 0;
		}
		return this.affected != null && this.affected.contains(to);
	}

	private static Pattern glob(String pattern) {
		StringBuilder regex = new StringBuilder();
		for (char c : pattern.toCharArray()) {
			regex.append((c == '*') ? ".*" : Pattern.quote(String.valueOf(c)));
		}
		return Pattern.compile(regex.toString());
	}

	/** 누가 고치는가 */
	public enum Fix {

		/** 레시피가 고쳤다. 결과만 확인한다 */
		@JsonProperty("auto")
		AUTO,

		/** 레시피가 바꿨지만 사람이 확인해야 한다 */
		@JsonProperty("assisted")
		ASSISTED,

		/** 사람이 처리한다 */
		@JsonProperty("manual")
		MANUAL

	}

	public record When(List<String> dependencies) {
	}

}
