package com.eottabom.migration.guide;

import com.eottabom.migration.version.Versions;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 라이브러리 버전 범위 [from, until). 가이드에는 두 칸짜리 배열로 적는다.
 */
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
@JsonPropertyOrder({ "from", "until" })
public record VersionRange(String from, String until) {

	boolean contains(String version) {
		return Versions.compare(version, this.from) >= 0 && Versions.compare(version, this.until) < 0;
	}

}
