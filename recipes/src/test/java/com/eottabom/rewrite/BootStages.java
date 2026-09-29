package com.eottabom.rewrite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 러너가 아는 Boot stage (guides/boot/ 의 파일, 버전 순서). 레시피 테스트와 생성기가 stage 목록을 따로 적지 않고 이 목록을
 * 쓴다. 새 stage 는 guides/boot/ 와 spring-boot.yml 에만 추가한다.
 */
public final class BootStages {

	static final Path GUIDES = Path.of("../guides");

	private BootStages() {
	}

	/** 레시피 이름에 쓰는 형태. 예) 3_0, 4_1 */
	public static List<String> suffixes() {
		try (Stream<Path> files = Files.list(GUIDES.resolve("boot"))) {
			return files.map((file) -> file.getFileName().toString().replace(".yml", ""))
				.sorted(BootStages::compare)
				.map((version) -> version.replace('.', '_'))
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static int compare(String a, String b) {
		String[] x = a.split("\\.");
		String[] y = b.split("\\.");
		int major = Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0]));
		return (major != 0) ? major : Integer.compare(Integer.parseInt(x[1]), Integer.parseInt(y[1]));
	}

}
