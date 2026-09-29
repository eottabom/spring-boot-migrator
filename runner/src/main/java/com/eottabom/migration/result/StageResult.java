package com.eottabom.migration.result;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.eottabom.migration.guide.FailureHint;
import com.eottabom.migration.misc.AtomicFiles;
import com.eottabom.migration.misc.TextFiles;
import com.eottabom.migration.result.RecipeChanges.CustomChange;
import com.eottabom.migration.result.TestReport.PropertyChange;
import com.eottabom.migration.result.TestReport.TestFailure;
import org.jspecify.annotations.Nullable;

/**
 * stage 하나의 결과. 로그와 테스트 결과를 읽어 모으고({@link #assess(Input)}) result.md 와 result.json 으로 쓴다.
 * result.json 의 형식은 schema/result.schema.json 이다.
 *
 * @param covers 이 stage 가 다룬 stage 이름 (--mode=all 이면 여러 개)
 * @param buildFailureExisting 빌드 실패가 원본에서도 실패하던 태스크 때문이다 (기존 문제로 표시)
 * @param deprecationFixes deprecated API 를 바꾼 대체 레시피
 * @param detect detect 레시피가 찾은 위치
 * @param checklist 러너가 guides/ 에서 고른 체크리스트 항목
 * @param source stage 가이드의 공식 문서
 */
public record StageResult(String stage, List<String> covers, Outcome compile, Outcome build,
		boolean buildFailureExisting, Tests tests, CompileWarnings warnings, List<String> deprecationFixes,
		Properties properties, List<DetectedItem> detect, Changes changes, DependencyChanges deps,
		List<ReportedChecklistItem> checklist, @Nullable String source) {

	private static final int MAX_CODE = 160;

	private static final Pattern DETECT_MARKER = Pattern.compile("/\\*~~(\\([^)]*\\))?>\\*/");

	public static StageResult assess(Input in) {
		CompileWarnings warnings = CompileWarnings.collect(in.compileLog(), in.projectDir());
		TestReport tests = TestReport.collect(in.projectDir(), in.failureHints(), in.baselineFailedTests(),
				in.testResults());
		RecipeChanges changes = RecipeChanges.collect(in.rewriteLog(), in.projectRecipes());
		DependencyChanges deps = DependencyChanges.compare(DependencyChanges.readVersions(in.versionsBefore()),
				DependencyChanges.readVersions(in.versionsAfter()));
		List<String> unreadable = in.unreadableResults()
			.stream()
			.map((file) -> in.projectDir().relativize(file).toString())
			.toList();
		return new StageResult(in.stage(), in.covers(), in.compile(), in.build(), in.buildFailureExisting(),
				new Tests(tests.total(), tests.failures(), List.copyOf(in.flakyTests()), unreadable), warnings,
				in.deprecationFixes(), new Properties(tests.renamed(), tests.unsupported()), detected(in.detectPatch()),
				new Changes(changes.customChanges(), changes.upstreamOnly(), List.copyOf(changes.changed())), deps,
				in.checklist(), in.source());
	}

	public StageSummary summary() {
		return StageSummary.of(this);
	}

	public void write(Path markdown, Path json) {
		AtomicFiles.write(markdown, ResultMarkdown.write(this));
		ResultJson.write(json, this);
	}

	List<TestFailure> newFailures() {
		return this.tests.failures().stream().filter((failure) -> !failure.existing()).toList();
	}

	List<TestFailure> existingFailures() {
		return this.tests.failures().stream().filter(TestFailure::existing).toList();
	}

	/** detect 레시피의 ~~&gt; 표시가 붙은 줄 */
	private static List<DetectedItem> detected(Path detectPatch) {
		Set<DetectedItem> items = new LinkedHashSet<>();
		String current = null;
		for (String line : TextFiles.readLines(detectPatch)) {
			if (line.startsWith("+++ b/")) {
				current = line.substring(6).trim();
			}
			else if (line.startsWith("+") && line.contains("~~>") && current != null) {
				String code = DETECT_MARKER.matcher(line.substring(1)).replaceAll("").trim();
				items.add(new DetectedItem(current, (code.length() > MAX_CODE) ? code.substring(0, MAX_CODE) : code));
			}
		}
		return List.copyOf(items);
	}

	/**
	 * @param flaky stage 에서 실패했다가 다시 돌려 통과한 테스트 id
	 * @param unreadable 끝까지 읽지 못한 테스트 결과 파일 (프로젝트 기준 경로)
	 */
	public record Tests(int total, List<TestFailure> failures, List<String> flaky, List<String> unreadable) {
	}

	public record Properties(List<PropertyChange> renamed, List<PropertyChange> unsupported) {
	}

	/**
	 * @param code 표시를 지운 코드 한 줄
	 */
	public record DetectedItem(String file, String code) {
	}

	/**
	 * @param custom custom 레시피와 프로젝트 레시피별로 바꾼 파일
	 * @param upstream custom 레시피가 바꾸지 않고 upstream 레시피만 바꾼 파일
	 * @param files stage 에서 바뀐 모든 파일
	 */
	public record Changes(List<CustomChange> custom, List<String> upstream, List<String> files) {
	}

	/**
	 * @param projectRecipes 대상 프로젝트의 .rewrite/ 레시피 이름 (custom 변경으로 센다)
	 * @param testResults 이 stage 의 build 게이트가 만든 테스트 결과 파일
	 * @param unreadableResults 끝까지 읽지 못한 테스트 결과 파일
	 */
	public record Input(String stage, List<String> covers, Path projectDir, Path compileLog, Path rewriteLog,
			Path detectPatch, Path versionsBefore, Path versionsAfter, Outcome compile, Outcome build,
			boolean buildFailureExisting, List<ReportedChecklistItem> checklist, @Nullable String source,
			List<FailureHint> failureHints, Set<String> projectRecipes, Set<String> baselineFailedTests,
			Set<String> flakyTests, List<Path> testResults, List<Path> unreadableResults,
			List<String> deprecationFixes) {
	}

}
