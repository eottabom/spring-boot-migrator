package com.eottabom.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 테스트용 git 저장소. 전역 git 설정 없이도 커밋되도록 사용자 설정을 붙여 실행한다.
 */
public final class GitFixture {

	private GitFixture() {
	}

	/** 파일을 쓰고 main 브랜치로 첫 커밋을 만든다 */
	public static void init(Path dir) {
		git(dir, "init", "-q", "-b", "main");
		git(dir, "config", "user.email", "t@t");
		git(dir, "config", "user.name", "t");
		git(dir, "add", "-A");
		git(dir, "commit", "-q", "--allow-empty", "-m", "init");
	}

	/** 표준 출력과 오류를 합친 결과 */
	public static String git(Path dir, String... args) {
		List<String> command = new ArrayList<>(List.of("git", "-c", "user.email=t@t", "-c", "user.name=t"));
		command.addAll(List.of(args));
		try {
			Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
			String out = new String(process.getInputStream().readAllBytes());
			process.waitFor();
			return out;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	public static void write(Path file, String content) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
