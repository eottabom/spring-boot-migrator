package com.eottabom.migration.gradle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.gradle.api.logging.Logger;
import org.jspecify.annotations.Nullable;

/** 대상 프로젝트의 Gradle wrapper 를 별도 프로세스로 실행한다. 대상은 자기 Gradle, 플러그인, JDK 로 돌아야 한다. */
public record GradleWrapperProcess(Path projectDir, @Nullable String javaHome, @Nullable String jvmArgs,
		Duration timeout, InitScripts scripts, Logger logger) implements ProjectGradle {

	private static final long KILL_WAIT_SECONDS = 10;

	private static final long DAEMON_IDLE_TIMEOUT_MS = 600_000;

	private static final long PROGRESS_INTERVAL_MS = 20_000;

	private static final boolean WINDOWS = System.getProperty("os.name", "")
		.toLowerCase(Locale.ROOT)
		.startsWith("windows");

	/** 러너가 정하는 메모리 옵션. 대상 프로젝트의 org.gradle.jvmargs 에서 이 옵션만 바꾸고 나머지는 둔다 */
	// -Xms 도 뺀다. 프로젝트의 -Xms 가 러너의 -Xmx 보다 크면 데몬이 뜨지 않는다
	private static final List<String> MEMORY_OPTIONS = List.of("-Xmx", "-Xms", "-XX:MaxMetaspaceSize=");

	/**
	 * @param jvmArgs 대상 Gradle 데몬 JVM 옵션. null 이면 대상 프로젝트의 org.gradle.jvmargs 에
	 * {@link #defaultJvmArgs()} 의 메모리 옵션만 바꿔 쓴다 (명령행 -Dorg.gradle.jvmargs 는
	 * gradle.properties 값을 통째로 대신하므로 file.encoding, --add-exports 같은 프로젝트 옵션을 잃지 않도록)
	 * @param timeout 한 번 실행의 제한 시간. 넘으면 프로세스를 종료하고 실패로 본다. 0 이면 제한 없음
	 */
	public GradleWrapperProcess {
		jvmArgs = (jvmArgs != null) ? jvmArgs : mergeJvmArgs(projectJvmArgs(projectDir), defaultJvmArgs());
	}

	/** 대상 프로젝트 gradle.properties 의 org.gradle.jvmargs (없으면 빈 문자열) */
	static String projectJvmArgs(Path projectDir) {
		Path file = projectDir.resolve("gradle.properties");
		if (!Files.isRegularFile(file)) {
			return "";
		}
		Properties properties = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
			properties.load(in);
		}
		catch (IOException ex) {
			return "";
		}
		return properties.getProperty("org.gradle.jvmargs", "").trim();
	}

	/** project 의 옵션에서 메모리 옵션만 runner 의 값으로 바꾼다 */
	static String mergeJvmArgs(String project, String runner) {
		List<String> merged = new ArrayList<>();
		for (String option : project.split("\\s+")) {
			if (!option.isEmpty() && MEMORY_OPTIONS.stream().noneMatch(option::startsWith)) {
				merged.add(option);
			}
		}
		merged.addAll(List.of(runner.split("\\s+")));
		return String.join(" ", merged);
	}

	/**
	 * OpenRewrite 는 전체 LST 를 메모리에 올린다. 최대 6g 로 하되 CI 컨테이너에서 죽지 않도록 장비 메모리의 절반을 넘기지 않는다.
	 */
	static String defaultJvmArgs() {
		long totalMb = 8192;
		if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
			totalMb = os.getTotalMemorySize() / (1024 * 1024);
		}
		long heapMb = Math.max(1024, Math.min(6144, totalMb / 2));
		return "-Xmx" + heapMb + "m -XX:MaxMetaspaceSize=1g";
	}

	/**
	 * rewriteRun, rewriteDryRun. clean 은 남은 QueryDSL Q-class 로 APT 가 실패하지 않도록,
	 * --no-daemon 은 데몬이 옛 레시피 jar 를 캐시하지 않도록 한다.
	 */
	@Override
	public boolean rewrite(Path log, RewriteTask task, String recipe, @Nullable Path configFile) {
		List<String> args = new ArrayList<>(
				List.of("--no-daemon", "--init-script", this.scripts.rewriteInit().toString(), "clean", task.taskName(),
						"-Drewrite.activeRecipe=" + recipe, "-PrewriteRecipeLibs=" + this.scripts.recipeLibs()));
		if (configFile != null) {
			args.add("-PrewriteConfigFile=" + configFile);
		}
		return run(log, args);
	}

	@Override
	public boolean verify(Path log, List<String> args) {
		return run(log, withVerifyInit(args));
	}

	@Override
	public boolean verifyQuietly(List<String> args) {
		return runQuietly(withVerifyInit(args));
	}

	private List<String> withVerifyInit(List<String> args) {
		List<String> all = new ArrayList<>(List.of("--init-script", this.scripts.verifyInit().toString()));
		all.addAll(args);
		return all;
	}

	/** 출력은 log 파일로 보내고, 실행 중에는 경과 시간과 현재 태스크를 주기적으로 찍는다 */
	public boolean run(Path log, List<String> args) {
		long start = System.currentTimeMillis();
		AtomicReference<String> currentTask = new AtomicReference<>("준비 중");
		Process process = null;
		try {
			Files.createDirectories(log.getParent());
			process = start(args);
			Process started = process;
			Thread pump = new Thread(() -> pump(started, log, currentTask), "target-gradle-output");
			pump.start();
			long lastReport = start;
			while (!process.waitFor(2, TimeUnit.SECONDS)) {
				long now = System.currentTimeMillis();
				if (timedOut(start, now)) {
					List<Long> survivors = kill(process);
					pump.join();
					Files.writeString(log,
							"\n!! 시간 초과 (" + this.timeout.toMinutes()
									+ "분). spring-boot-migrator.yml 의 build.timeoutMinutes 로 바꿀 수 있어요\n"
									+ (survivors.isEmpty() ? "" : "!! 종료되지 않은 프로세스 " + survivors + "\n"),
							StandardOpenOption.APPEND);
					this.logger.error("!! {} 가 {}분 안에 끝나지 않아 종료했어요 → {}", String.join(" ", args),
							this.timeout.toMinutes(), log);
					return false;
				}
				if (now - lastReport >= PROGRESS_INTERVAL_MS) {
					lastReport = now;
					this.logger.lifecycle("   {}  {}", elapsed(start), currentTask.get());
				}
			}
			pump.join();
			this.logger.lifecycle("   소요 {}", elapsed(start));
			return process.exitValue() == 0;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			throw interrupted(process, ex);
		}
	}

	/** 결과만 필요하고 로그는 남기지 않는 실행. */
	public boolean runQuietly(List<String> args) {
		Process process = null;
		try {
			process = start(args);
			Process started = process;
			Thread drain = new Thread(() -> {
				try {
					started.getInputStream().transferTo(OutputStream.nullOutputStream());
				}
				catch (IOException ex) {
				}
			}, "target-gradle-quiet");
			drain.start();
			boolean finished = this.timeout.isZero() ? process.waitFor() >= 0
					: process.waitFor(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!finished) {
				kill(process);
				drain.join();
				return false;
			}
			drain.join();
			return process.exitValue() == 0;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			throw interrupted(process, ex);
		}
	}

	/** 러너가 취소되면 대상 Gradle 도 끝낸다. 남겨 두면 프로젝트 파일을 계속 쓴다 */
	private IllegalStateException interrupted(@Nullable Process process, InterruptedException ex) {
		if (process != null) {
			kill(process);
		}
		Thread.currentThread().interrupt();
		return new IllegalStateException(ex);
	}

	private boolean timedOut(long start, long now) {
		return !this.timeout.isZero() && now - start > this.timeout.toMillis();
	}

	/**
	 * gradlew 스크립트가 띄운 Gradle JVM 까지 종료하고 끝날 때까지 기다린다.
	 * @return 기다린 뒤에도 남은 프로세스 id
	 */
	private List<Long> kill(Process process) {
		List<ProcessHandle> handles = new ArrayList<>(process.descendants().toList());
		handles.add(process.toHandle());
		handles.forEach(ProcessHandle::destroyForcibly);
		List<Long> survivors = handles.stream().filter((handle) -> !exited(handle)).map(ProcessHandle::pid).toList();
		if (!survivors.isEmpty()) {
			this.logger.error("!! 종료되지 않은 대상 Gradle 프로세스 {}", survivors);
		}
		return survivors;
	}

	private static boolean exited(ProcessHandle handle) {
		try {
			handle.onExit().get(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
			return true;
		}
		catch (TimeoutException ex) {
			return false;
		}
		catch (ExecutionException ex) {
			return !handle.isAlive();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return !handle.isAlive();
		}
	}

	private Process start(List<String> args) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(this.projectDir.resolve(WINDOWS ? "gradlew.bat" : "gradlew").toString());
		command.add("-Dorg.gradle.jvmargs=" + this.jvmArgs);
		command.add("--console=plain");
		// 러너가 띄운 데몬이 기본 3시간 동안 남지 않도록 한다
		command.add("-Dorg.gradle.daemon.idletimeout=" + DAEMON_IDLE_TIMEOUT_MS);
		// OpenRewrite 플러그인과 init script 태스크는 configuration cache 를 지원하지 않아 끈다
		command.add("--no-configuration-cache");
		command.addAll(args);
		ProcessBuilder builder = new ProcessBuilder(command).directory(this.projectDir.toFile())
			.redirectErrorStream(true);
		if (this.javaHome != null) {
			builder.environment().put("JAVA_HOME", this.javaHome);
		}
		return builder.start();
	}

	private static void pump(Process process, Path log, AtomicReference<String> currentTask) {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
				Writer out = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
			String line;
			while ((line = in.readLine()) != null) {
				out.write(line);
				out.write('\n');
				if (line.startsWith("> Task ")) {
					currentTask.set(line.substring(7));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String elapsed(long start) {
		long seconds = (System.currentTimeMillis() - start) / 1000;
		return String.format("%02d:%02d", seconds / 60, seconds % 60);
	}
}
