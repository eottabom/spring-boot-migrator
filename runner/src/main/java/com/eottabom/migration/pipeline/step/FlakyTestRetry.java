package com.eottabom.migration.pipeline.step;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.result.TestResults;
import com.eottabom.migration.workspace.StageFiles;

/**
 * 새로 실패한 테스트의 클래스만 다시 돌린다. 다시 돌려 통과한 테스트는 불안정한 테스트로 보고 실패로 세지 않는다.
 *
 * <p>
 * 재시도의 test 태스크는 모듈의 결과 XML 디렉토리를 비우고 필터에 걸린 클래스만 다시 쓴다. 결과가 build 의 전체 결과(다시 돌린 클래스는 마지막
 * 결과)를 읽도록, 다시 돌리기 전에 결과를 stage 폴더에 복사해 두고 끝나면 지워진 파일만 되돌린다.
 */
final class FlakyTestRetry {

	private final ProjectGradle gradle;

	private final RunnerConsole console;

	private final Path projectDir;

	private final int retries;

	/**
	 * @param retries 다시 돌리는 횟수 (0 이면 다시 돌리지 않는다)
	 */
	FlakyTestRetry(ProjectGradle gradle, RunnerConsole console, Path projectDir, int retries) {
		this.gradle = gradle;
		this.console = console;
		this.projectDir = projectDir;
		this.retries = retries;
	}

	/**
	 * @param results build 가 쓴 결과 XML (다시 돌린 뒤 지워지면 되돌린다)
	 */
	Retried retry(StageFiles files, Set<String> failedTests, List<Path> results) {
		Set<String> failing = new TreeSet<>(failedTests);
		Set<String> flaky = new TreeSet<>();
		if (this.retries > 0 && !failing.isEmpty()) {
			Path kept = files.keptResults();
			copy(results, kept);
			try {
				retry(files, failing, flaky, kept);
			}
			finally {
				restoreMissing(kept);
			}
		}
		if (!flaky.isEmpty()) {
			this.console.line("   다시 돌려서 통과한 불안정한 테스트 {}개 (stage 를 막지 않아요)", flaky.size());
		}
		return new Retried(failing, flaky);
	}

	private void retry(StageFiles files, Set<String> failing, Set<String> flaky, Path kept) {
		for (int attempt = 1; attempt <= this.retries && !failing.isEmpty(); attempt++) {
			this.console.line("   새로 실패한 테스트 {}개를 다시 돌려요 ({}/{})", failing.size(), attempt, this.retries);
			List<String> args = new ArrayList<>(List.of("test", "--continue"));
			for (String testClass : testClasses(failing)) {
				args.addAll(List.of("--tests", testClass));
			}
			TestRun.Result run = new TestRun(this.gradle, this.projectDir).run(files.retryLog(attempt),
					files.retryTestDirs(attempt), args, false);
			// 이번 결과가 다음 회차에 지워져도 마지막 결과로 되돌린다
			copy(run.files(), kept);
			if (!run.built() || !run.tests().complete()) {
				this.console.error("재시도 실행 또는 결과 수집 실패. 기존 테스트 실패를 그대로 둬요");
				continue;
			}
			Set<String> recovered = new TreeSet<>(run.tests().passedTests());
			recovered.retainAll(failing);
			flaky.addAll(recovered);
			failing.removeAll(recovered);
		}
	}

	/** 테스트 id 의 최상위 클래스 필터. 중첩 클래스도 함께 돌도록 * 를 붙인다 */
	static Set<String> testClasses(Set<String> testIds) {
		Set<String> classes = new TreeSet<>();
		for (String id : testIds) {
			String testClass = TestResults.testClass(id);
			int nested = testClass.indexOf('$');
			classes.add(((nested >= 0) ? testClass.substring(0, nested) : testClass) + "*");
		}
		return classes;
	}

	private void copy(List<Path> files, Path kept) {
		for (Path file : files) {
			if (!file.startsWith(this.projectDir)) {
				// 프로젝트 밖으로 뺀 결과 디렉토리는 보관하지 않는다
				continue;
			}
			Path target = kept.resolve(this.projectDir.relativize(file).toString());
			try {
				Files.createDirectories(target.getParent());
				Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException ex) {
				// 복사하지 못한 결과는 되돌리지 못할 뿐이다. 게이트 판정은 이미 읽은 결과로 한다
			}
		}
	}

	/** 판정은 끝났고 되돌리는 것은 결과용이라 실패해도 멈추지 않는다 */
	private void restoreMissing(Path kept) {
		if (!Files.isDirectory(kept)) {
			return;
		}
		try (Stream<Path> files = Files.walk(kept)) {
			for (Path copy : files.filter(Files::isRegularFile).toList()) {
				Path original = this.projectDir.resolve(kept.relativize(copy).toString());
				if (!Files.exists(original)) {
					Files.createDirectories(original.getParent());
					Files.copy(copy, original);
				}
			}
		}
		catch (IOException | UncheckedIOException ex) {
			this.console.error("다시 돌리기 전 테스트 결과를 되돌리지 못했어요. 결과의 테스트 목록이 일부 빠질 수 있어요: " + ex.getMessage());
		}
		deleteQuietly(kept);
	}

	private static void deleteQuietly(Path dir) {
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(file);
			}
		}
		catch (IOException | UncheckedIOException ignored) {
		}
	}

	/**
	 * @param failing 다시 돌려도 실패한 테스트
	 * @param flaky 다시 돌려 통과한 테스트
	 */
	record Retried(Set<String> failing, Set<String> flaky) {
	}

}
