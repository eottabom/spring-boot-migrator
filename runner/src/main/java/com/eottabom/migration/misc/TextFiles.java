package com.eottabom.migration.misc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 러너가 남긴 로그와 상태 파일 읽기. 파일이 없으면 빈 내용으로 본다.
 */
public final class TextFiles {

	private TextFiles() {
	}

	/** 파일의 줄. 파일이 없으면 빈 목록 */
	public static List<String> readLines(Path file) {
		if (file == null || !Files.exists(file)) {
			return List.of();
		}
		return read(file).lines().toList();
	}

	public static String read(Path file) {
		try {
			return Files.exists(file) ? Files.readString(file) : "";
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static long countMatches(Path file, String regex) {
		Pattern pattern = Pattern.compile(regex);
		return read(file).lines().filter((line) -> pattern.matcher(line).find()).count();
	}

}
