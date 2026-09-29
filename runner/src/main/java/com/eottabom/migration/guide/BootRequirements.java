package com.eottabom.migration.guide;

import java.util.Map;
import java.util.TreeMap;

import com.eottabom.migration.misc.Versions;

/**
 * Boot 의 시스템 요구 사항.
 *
 * @param gradle Gradle major 별 최소 버전. 목록에 없는 major 는 공식 지원 밖
 */
public record BootRequirements(JavaRange java, Map<Integer, String> gradle, String framework, SpringCloud springCloud,
		String springCloudAws) {

	public BootRequirements {
		gradle = new TreeMap<>(gradle);
	}

	public GradleSupport gradleSupport(String gradleVersion) {
		int major = Versions.major(gradleVersion);
		String min = this.gradle.get(major);
		if (min != null) {
			return (Versions.compare(gradleVersion, min) >= 0) ? GradleSupport.SUPPORTED : GradleSupport.TOO_OLD;
		}
		return (major < this.gradle.keySet().stream().min(Integer::compare).orElseThrow()) ? GradleSupport.TOO_OLD
				: GradleSupport.NOT_LISTED;
	}

	/** 예) "7.6.4+ / 8.4+" */
	public String gradleRange() {
		return String.join(" / ", this.gradle.values().stream().map((version) -> version + "+").toList());
	}

	public boolean supportsJava(int version) {
		return version >= this.java.min() && version <= this.java.max();
	}

	/**
	 * @param max 공식 문서의 "compatible up to and including"
	 */
	public record JavaRange(int min, int max) {
	}

	/**
	 * @param since 그 트레인에서 이 Boot 를 지원하기 시작한 버전
	 */
	public record SpringCloud(String train, String since) {
	}

	public enum GradleSupport {

		SUPPORTED, TOO_OLD, NOT_LISTED

	}

}
