package com.eottabom.migration.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * 러너 통합 테스트용 가짜 대상 빌드. 명령 인자로 무엇을 하려는지 알아보고, 테스트가 정해 둔 결과를 순서대로 돌려준다. 정해 둔 결과가 없으면 성공으로
 * 본다.
 */
public final class FakeProjectGradle implements ProjectGradle {

	public final Path projectDir;

	public final Deque<Consumer<Path>> rewrites;

	public final Deque<Boolean> compiles;

	public final Deque<BuildOutcome> builds;

	/** 실패한 테스트만 다시 돌릴 때(--tests) 의 결과. 정해 두지 않으면 직전 빌드와 같은 테스트가 다시 실패한다 */
	public final Deque<BuildOutcome> retries = new ArrayDeque<>();

	/** 컴파일 로그에 더할 줄 (javac 경고). 컴파일할 때마다 하나씩 쓴다 */
	public final Deque<String> compileWarnings = new ArrayDeque<>();

	private BuildOutcome lastBuild = BuildOutcome.pass();

	public BuildOutcome baseline = BuildOutcome.pass();

	/** false 면 시작할 때의 의존성 버전 수집(migrationResolvedVersions 만 도는 실행)이 실패한다 */
	public boolean resolvesStartVersions = true;

	public final List<String> calls;

	public FakeProjectGradle(Path projectDir) {
		this(projectDir, new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>(), new ArrayList<>());
	}

	private FakeProjectGradle(Path projectDir, Deque<Consumer<Path>> rewrites, Deque<Boolean> compiles,
			Deque<BuildOutcome> builds, List<String> calls) {
		this.projectDir = projectDir;
		this.rewrites = rewrites;
		this.compiles = compiles;
		this.builds = builds;
		this.calls = calls;
	}

	/** 같은 결과 목록을 쓰면서 다른 디렉토리(preview 의 임시 worktree)에서 실행한다 */
	public FakeProjectGradle at(Path dir) {
		if (dir.equals(this.projectDir)) {
			return this;
		}
		FakeProjectGradle other = new FakeProjectGradle(dir, this.rewrites, this.compiles, this.builds, this.calls);
		other.retries.addAll(this.retries);
		other.baseline = this.baseline;
		other.resolvesStartVersions = this.resolvesStartVersions;
		return other;
	}

	@Override
	public @Nullable String javaHome() {
		return null;
	}

	@Override
	public boolean runQuietly(List<String> args) {
		return run(null, args);
	}

	@Override
	public boolean rewrite(Path log, String task, String recipe, Path rewriteInit, Path recipeLibs,
			@Nullable Path configFile) {
		this.calls.add(task + " " + recipe);
		if (task.equals("rewriteRun") && !this.rewrites.isEmpty()) {
			this.rewrites.poll().accept(this.projectDir);
		}
		write(log, "BUILD SUCCESSFUL");
		return true;
	}

	@Override
	public boolean run(@Nullable Path log, List<String> args) {
		String cmd = String.join(" ", args);
		this.calls.add(cmd.replaceAll("--init-script \\S+ ", ""));
		if (!this.resolvesStartVersions && args.contains("migrationResolvedVersions")
				&& !args.contains("compileJava")) {
			write(log, "BUILD FAILED");
			return false;
		}
		args.stream()
			.filter((arg) -> arg.startsWith("-PmigrationVersionsOut="))
			.findFirst()
			.ifPresent((arg) -> write(Path.of(arg.substring(arg.indexOf('=') + 1)),
					"org.springframework.boot:spring-boot=3.4.0\n"));
		if (log != null && log.getFileName().toString().startsWith("baseline")) {
			return finish(log, this.baseline, args);
		}
		if (args.contains("--tests")) {
			return finish(log, this.retries.isEmpty() ? this.lastBuild : this.retries.poll());
		}
		if (args.contains("compileJava")) {
			boolean ok = this.compiles.isEmpty() || this.compiles.poll();
			String warnings = this.compileWarnings.isEmpty() ? "" : this.compileWarnings.poll() + "\n";
			write(log, warnings
					+ (ok ? "BUILD SUCCESSFUL" : "Compilation failed\nExecution failed for task ':compileJava'."));
			return ok;
		}
		if (args.contains("build")) {
			this.lastBuild = this.builds.isEmpty() ? BuildOutcome.pass() : this.builds.poll();
			return finish(log, this.lastBuild, args);
		}
		write(log, "BUILD SUCCESSFUL");
		return true;
	}

	public long count(String fragment) {
		return this.calls.stream().filter((call) -> call.contains(fragment)).count();
	}

	private boolean finish(@Nullable Path log, BuildOutcome outcome) {
		return finish(log, outcome, List.of());
	}

	/**
	 * 실패 태스크는 verify.init.gradle 처럼 -PmigrationFailedTasksOut 파일에 쓰고, 로그에는 번역된 문구만 남긴다
	 * (러너가 로그 문구에 기대지 않는지 확인). 파일 인자가 없으면 Gradle 의 영어 문구로 로그에 남긴다.
	 */
	private boolean finish(@Nullable Path log, BuildOutcome outcome, List<String> args) {
		Path failedTasksOut = args.stream()
			.filter((arg) -> arg.startsWith("-PmigrationFailedTasksOut="))
			.map((arg) -> Path.of(arg.substring(arg.indexOf('=') + 1)))
			.findFirst()
			.orElse(null);
		StringBuilder out = new StringBuilder();
		if (failedTasksOut != null) {
			write(failedTasksOut, String.join("\n", outcome.failedTasks()));
			outcome.failedTasks().forEach((task) -> out.append("* 문제: 태스크 ").append(task).append(" 실행 실패\n"));
		}
		else {
			outcome.failedTasks()
				.forEach((task) -> out.append("* What went wrong:\nExecution failed for task '")
					.append(task)
					.append("'.\n"));
		}
		write(log, (out.length() == 0) ? (outcome.ok() ? "BUILD SUCCESSFUL" : "BUILD FAILED") : out.toString());
		// 테스트 결과 XML (빌드 산출물이라 .gitignore 의 build/ 아래)
		Path xml = this.projectDir.resolve("build/test-results/test/TEST-demo.AppTest.xml");
		StringBuilder cases = new StringBuilder();
		for (int i = 0; i < outcome.failedTests(); i++) {
			cases.append("<testcase classname=\"demo.AppTest\" name=\"t")
				.append(i)
				.append("\"><failure message=\"boom\">boom</failure></testcase>");
		}
		if (outcome.failedTests() == 0) {
			cases.append("<testcase classname=\"demo.AppTest\" name=\"t0\"/>");
		}
		write(xml, "<testsuite name=\"demo.AppTest\" tests=\"" + Math.max(1, outcome.failedTests()) + "\" failures=\""
				+ outcome.failedTests() + "\" errors=\"0\">" + cases + "</testsuite>");
		// 빌드가 만든 추적 안 되는 파일 (.gitignore 에 없는 산출물). 커밋에 섞이면 안 된다
		write(this.projectDir.resolve("test-output.log"), "junk");
		return outcome.ok();
	}

	public static void write(@Nullable Path file, String content) {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * @param failedTasks 로그에 남길 실패 태스크 (Gradle 의 "Execution failed for task" 형식)
	 */
	public record BuildOutcome(boolean ok, int failedTests, List<String> failedTasks) {
		public static BuildOutcome pass() {
			return new BuildOutcome(true, 0, List.of());
		}
	}

}
