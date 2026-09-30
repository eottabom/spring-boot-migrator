package com.eottabom.migration.stage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 한 실행 안에서 stage 에 붙는 번호와 stage. 결과 폴더 이름이 된다.
 *
 * @param order 지난 실행까지 이어서 붙이는 번호 (1 부터)
 */
public record StageTag(int order, StageId stage) {

	private static final Pattern DIR_NAME = Pattern.compile("^(\\d{2,})-(.+)$");

	private static final String BOOT_PREFIX = "boot-";

	public static boolean isDirName(String name) {
		return DIR_NAME.matcher(name).matches();
	}

	/** {@link #dirName()} 으로 쓴 이름을 되읽는다 */
	@JsonCreator
	public static StageTag parse(String dirName) {
		Matcher tag = DIR_NAME.matcher(dirName);
		if (!tag.matches()) {
			throw new IllegalArgumentException("stage 폴더 이름이 아니에요: " + dirName);
		}
		String stage = tag.group(2);
		return new StageTag(Integer.parseInt(tag.group(1)), stage.startsWith(BOOT_PREFIX)
				? StageId.boot(stage.substring(BOOT_PREFIX.length())) : StageId.parse(stage));
	}

	/** 결과 폴더 이름. 예) 03-boot-3.4, 06-java25, 02-gradle8.14 */
	@JsonValue
	public String dirName() {
		String stageName = (this.stage.kind() == StageId.Kind.BOOT) ? BOOT_PREFIX + this.stage.version()
				: this.stage.name();
		return String.format("%02d-%s", this.order, stageName);
	}

	/** 레시피 이름에 붙일 수 있게 글자와 숫자만 남긴 이름. 예) 03_boot_3_4 */
	public String recipeSuffix() {
		return dirName().replaceAll("[^A-Za-z0-9]", "_");
	}

	@Override
	public String toString() {
		return dirName();
	}

}
