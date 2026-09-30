package com.eottabom.migration.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** 짧게 끝나는 외부 명령(git, java_home) 실행. */
public final class Processes {

	private Processes() {
	}

	/** 명령의 표준 출력. 실패(0 이 아닌 종료 코드)하면 null. 표준 에러는 버린다 (경고 줄이 결과에 섞이지 않도록) */
	public static @Nullable String capture(Path dir, String... command) {
		return capture(dir, Map.of(), command);
	}

	public static @Nullable String capture(Path dir, Map<String, String> env, String... command) {
		return capture(dir, env, false, command);
	}

	/** 표준 출력과 표준 에러를 합친 출력 (java_home -V 처럼 결과를 표준 에러로 내는 명령) */
	public static @Nullable String captureWithErrors(Path dir, String... command) {
		return capture(dir, Map.of(), true, command);
	}

	private static @Nullable String capture(Path dir, Map<String, String> env, boolean withErrors, String... command) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
			builder.environment().putAll(env);
			if (withErrors) {
				builder.redirectErrorStream(true);
			}
			else {
				builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			}
			Process process = builder.start();
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return (process.waitFor() == 0) ? out : null;
		}
		catch (IOException ex) {
			return null;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/** 명령을 실행하고 종료 코드가 0 인지 돌려준다. out 이 있으면 표준 출력을 그 파일로 보낸다. */
	public static boolean run(Path dir, @Nullable Path out, List<String> command) {
		return run(dir, out, command, Map.of());
	}

	public static boolean run(Path dir, @Nullable Path out, List<String> command, Map<String, String> env) {
		return run(dir, out, null, command, env);
	}

	/** errors 가 있으면 표준 에러를 그 파일 끝에 붙인다 (hook 이나 git 이 실패한 이유를 남긴다) */
	public static boolean run(Path dir, @Nullable Path out, @Nullable Path errors, List<String> command,
			Map<String, String> env) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
			builder.environment().putAll(env);
			if (out != null) {
				builder.redirectOutput(out.toFile());
			}
			else {
				builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			}
			builder.redirectError((errors != null) ? ProcessBuilder.Redirect.appendTo(errors.toFile())
					: ProcessBuilder.Redirect.DISCARD);
			return builder.start().waitFor() == 0;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
