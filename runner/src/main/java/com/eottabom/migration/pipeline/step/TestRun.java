package com.eottabom.migration.pipeline.step;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.eottabom.migration.gradle.ProjectGradle;
import com.eottabom.migration.gradle.VerifyInitScript;
import com.eottabom.migration.io.TextFiles;
import com.eottabom.migration.result.TestResults;
import com.eottabom.migration.result.TestResults.Snapshot;

/**
 * 테스트가 도는 빌드 한 번. verify.init.gradle 을 붙여 실행하고 이 실행이 새로 쓴 결과 XML 만 모은다. 원본 빌드, stage 게이트,
 * 재시도, migrationVerify 가 같은 방법으로 결과를 센다.
 */
public record TestRun(ProjectGradle gradle, VerifyInitScript verifyInit, Path projectDir) {

	/**
	 * @param reportDirs 테스트 태스크가 결과 XML 을 쓴 디렉토리를 남길 파일 (-PmigrationTestResultsOut)
	 * @param requireResults 테스트를 돌린 Test 태스크마다 결과가 있어야 한다. --tests 로 거른 재시도는 필터에 걸린 클래스의
	 * 결과만 새로 쓴다
	 */
	public Result run(Path log, Path reportDirs, List<String> args, boolean requireResults) {
		Snapshot before = Snapshot.take(this.projectDir);
		List<String> all = new ArrayList<>(args);
		all.add("-PmigrationTestResultsOut=" + reportDirs);
		boolean built = this.gradle.run(log, this.verifyInit.args(all.toArray(String[]::new)));
		List<Path> dirs = TextFiles.readLines(reportDirs)
			.stream()
			.filter((line) -> !line.isBlank())
			.map((line) -> inProject(Path.of(line.trim())))
			.distinct()
			.toList();
		List<Path> files = before.changedFiles(dirs);
		// 테스트를 돌린 태스크의 결과가 하나도 없으면 결과를 못 찾은 것이다. 0개로 세면 게이트가 테스트 없이 통과한다
		// (테스트가 0개인 태스크는 verify.init.gradle 이 남기지 않는다)
		List<Path> missing = !requireResults ? List.of()
				: dirs.stream().filter((dir) -> files.stream().noneMatch((file) -> file.startsWith(dir))).toList();
		TestResults.Results tests = TestResults.read(this.projectDir, files).withUnreadable(missing);
		return new Result(built, files, tests);
	}

	/**
	 * Gradle 은 결과 디렉토리를 실제 경로로 알려 준다. 프로젝트 경로가 symlink 를 거치면(macOS 의 /tmp 등) 러너가 걷는 경로와
	 * 달라 같은 XML 을 두 번 세므로 프로젝트 경로 기준으로 바꾼다.
	 */
	private Path inProject(Path dir) {
		try {
			Path realProject = this.projectDir.toRealPath();
			Path realDir = dir.toRealPath();
			return realDir.startsWith(realProject) ? this.projectDir.resolve(realProject.relativize(realDir).toString())
					: dir;
		}
		catch (IOException ex) {
			// 결과 디렉토리가 없으면 그대로 둔다 (결과가 없는 태스크로 잡힌다)
			return dir;
		}
	}

	/**
	 * @param files 이 실행이 새로 쓰거나 바꾼 결과 XML
	 */
	public record Result(boolean built, List<Path> files, TestResults.Results tests) {
	}

}
