package com.eottabom.migration.result;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.eottabom.migration.guide.ChecklistItem.Fix;
import com.eottabom.migration.result.CompileWarnings.ApiWarning;
import com.eottabom.migration.result.DependencyChanges.VersionChange;
import com.eottabom.migration.result.TestReport.PropertyChange;
import com.eottabom.migration.result.TestReport.TestFailure;

/**
 * stage 결과 마크다운 (NN-stage/result.md). 요약 표 다음에 섹션을 순서대로 쓴다. result.json 과 같은 모델을 읽는다.
 */
final class ResultMarkdown {

	private static final List<ChecklistSection> CHECKLIST_SECTIONS = List.of(
			new ChecklistSection(Fix.MANUAL, "사람이 처리 (자동으로 바꾸지 않음)"),
			new ChecklistSection(Fix.ASSISTED, "레시피가 바꿨지만 확인 필요"), new ChecklistSection(Fix.AUTO, "레시피가 고침 (결과만 확인)"));

	private final List<String> lines = new ArrayList<>();

	private ResultMarkdown() {
	}

	static String write(StageResult result) {
		ResultMarkdown md = new ResultMarkdown();
		md.summary(result);
		md.changes(result.changes());
		md.deprecationFixes(result.deprecationFixes());
		md.newFailures(result.newFailures());
		md.existingFailures(result.existingFailures());
		md.flakyTests(result.tests().flaky());
		md.properties(result.properties());
		md.warnings("제거 예정 API 사용 ([removal])", "다음 stage 에서 컴파일이 깨질 수 있는 곳이에요. 대체 레시피가 없는 API 라 직접 정리해 주세요.",
				result.warnings().removal());
		md.warnings("deprecated API 사용", "지금은 괜찮지만 이후 버전에서 없어질 수 있어요.", result.warnings().deprecation());
		md.dependencies(result.deps());
		md.checklist(result);
		md.detected(result.detect());
		return String.join("\n", md.lines);
	}

	private void summary(StageResult result) {
		heading("# " + result.stage().title() + " 마이그레이션 결과");
		this.lines.addAll(result.summary().table());
		add("");
	}

	private void changes(StageResult.Changes changes) {
		if (changes.custom().isEmpty() && changes.upstream().isEmpty()) {
			return;
		}
		section("## 레시피 변경", "이 stage 에서 레시피가 바꾼 파일. 전체 변경은 stage.patch 참고.");
		if (!changes.custom().isEmpty()) {
			add("### custom");
			changes.custom()
				.forEach((change) -> add("- **" + change.recipe() + "** 로 바뀐 파일 " + joinCode(change.files(), 5)));
			add("");
		}
		if (!changes.upstream().isEmpty()) {
			add("### upstream", "- " + joinCode(changes.upstream(), 10), "");
		}
	}

	private void deprecationFixes(List<String> recipes) {
		if (recipes.isEmpty()) {
			return;
		}
		section("## deprecated API 대체", "컴파일 경고의 deprecated API 를 guides/ 의 대체 레시피로 바꾸고 다시 컴파일했어요.");
		recipes.forEach((recipe) -> add("- " + code(recipe)));
		add("");
	}

	/** 클래스별로 묶고, 같은 원인(예외와 메시지) 은 한 줄로 합친다. 많은 것부터 */
	private void newFailures(List<TestFailure> failures) {
		if (failures.isEmpty()) {
			return;
		}
		Map<String, List<TestFailure>> byClass = groupBy(failures, TestFailure::className);
		section("## 실패한 테스트 (" + failures.size() + "건, " + byClass.size() + "개 클래스)",
				"원인은 스택트레이스의 가장 안쪽 예외(Caused by) 기준. 전체 스택은 각 모듈의 build/test-results 참고.");
		byClass.entrySet().stream().sorted(largestFirst()).forEach((byTestClass) -> {
			String className = byTestClass.getKey();
			add("### " + simpleName(className) + " (" + byTestClass.getValue().size() + "건)", code(className), "");
			groupBy(byTestClass.getValue(), (failure) -> failure.exception() + "|" + failure.message()).values()
				.stream()
				.sorted(Comparator.comparingInt(List<TestFailure>::size).reversed())
				.forEach(this::failureCause);
			add("");
		});
	}

	private void failureCause(List<TestFailure> same) {
		TestFailure first = same.get(0);
		add("- **" + code(first.exception()) + "** " + first.message() + " (" + same.size() + "건)");
		if (first.firstProjectFrame() != null) {
			add("  - 스택의 첫 프로젝트 코드 프레임 " + code(first.firstProjectFrame()));
		}
		if (first.hint() != null) {
			add("  - " + first.hint());
		}
		List<String> names = same.stream().map(TestFailure::testName).toList();
		add("  - 해당 테스트 " + String.join(" / ", names.subList(0, Math.min(5, names.size())))
				+ ((names.size() > 5) ? " 외 " + (names.size() - 5) + "건" : ""));
	}

	private void existingFailures(List<TestFailure> failures) {
		if (failures.isEmpty()) {
			return;
		}
		section("## 원본에서도 실패하던 테스트 (" + failures.size() + "건)", "마이그레이션 전부터 실패하던 테스트라 stage 를 막지 않아요.");
		failures.forEach((failure) -> add("- " + code(failure.className()) + " " + failure.testName()));
		add("");
	}

	private void flakyTests(List<String> flakyTests) {
		if (flakyTests.isEmpty()) {
			return;
		}
		section("## 다시 돌려 통과한 테스트 (" + flakyTests.size() + "건)",
				"실패했다가 다시 돌려서 통과한 테스트라 stage 를 막지 않았어요. 실행 순서나 시간, 외부 자원에 따라 결과가 바뀌는 불안정한 테스트인지 봐 주세요.");
		flakyTests.forEach(
				(id) -> add("- " + code(id.substring(0, id.indexOf('#'))) + " " + id.substring(id.indexOf('#') + 1)));
		add("");
	}

	private void properties(StageResult.Properties properties) {
		if (properties.renamed().isEmpty() && properties.unsupported().isEmpty()) {
			heading("## 설정 키 변경 (spring-boot-properties-migrator)");
			add("테스트 로그에서 발견된 것 없음. 테스트 프로파일은 외부 설정 저장소를 끄는 경우가 많으니 개발/스테이징 배포 후",
					"기동 로그에서 `The use of configuration keys that` 를 검색해 다시 확인해 주세요.", "");
			return;
		}
		section("## 설정 키 변경 (spring-boot-properties-migrator)",
				"테스트 중 로딩된 설정에서 찾았어요. 저장소 밖(외부 설정 저장소)의 키가 나오면 그쪽을 고쳐야 해요.");
		propertyList("### 이름 변경됨 (지금은 임시로 자동 매핑 중)", properties.renamed());
		propertyList("### 지원 중단됨 (값이 무시됨)", properties.unsupported());
	}

	private void propertyList(String title, List<PropertyChange> changes) {
		if (changes.isEmpty()) {
			return;
		}
		add(title);
		changes.forEach((change) -> add(
				"- " + code(change.key()) + ((change.replacement() != null) ? " → " + code(change.replacement()) : "")
						+ " (" + change.source() + ")"));
		add("");
	}

	private void warnings(String title, String description, List<ApiWarning> warnings) {
		if (warnings.isEmpty()) {
			return;
		}
		section("## " + title, description);
		warnings.forEach((warning) -> add("- **" + warning.message() + "** " + warning.locations().size() + "곳 ("
				+ String.join(", ", warning.locations().subList(0, Math.min(3, warning.locations().size())))
				+ ((warning.locations().size() > 3) ? " …" : "") + ")"));
		add("");
	}

	private void dependencies(DependencyChanges deps) {
		if (deps.isEmpty()) {
			return;
		}
		section("## 의존성 버전 변경 (transitive 포함, 전 모듈)", "변경 " + deps.changed().size() + "개 / 추가 " + deps.added().size()
				+ "개 / 제거 " + deps.removed().size() + "개. 라이브러리 버그는 주로 여기서 나오니 major/minor 변경을 먼저 확인해 주세요.");
		List<VersionChange> important = deps.changed().stream().filter((change) -> !change.isPatch()).toList();
		if (!important.isEmpty()) {
			add("### major / minor 변경", "| 라이브러리 | 이전 | 이후 | 구분 |", "|---|---|---|---|");
			important.forEach((change) -> row(code(change.name()),
					change.before() + " | " + change.after() + " | " + change.level().label()));
			add("");
		}
		if (!deps.added().isEmpty()) {
			add("추가된 의존성 " + joinCode(deps.added(), 30), "");
		}
		if (!deps.removed().isEmpty()) {
			add("제거된 의존성 " + joinCode(deps.removed(), 30), "");
		}
	}

	private void checklist(StageResult result) {
		if (result.checklist().isEmpty()) {
			return;
		}
		section("## 체크리스트 (" + result.stage() + ")", "컴파일과 테스트가 통과해도 확인할 항목. guides/ 기준"
				+ ((result.source() != null) ? " (원문 " + result.source() + ")" : ""));
		for (ChecklistSection section : CHECKLIST_SECTIONS) {
			List<ReportedChecklistItem> items = result.checklist()
				.stream()
				.filter((item) -> item.fix() == section.fix())
				.toList();
			if (items.isEmpty()) {
				continue;
			}
			add("### " + section.title());
			for (ReportedChecklistItem item : items) {
				String link = (item.source() != null) ? " [원문](" + item.source() + ")" : "";
				add("- **" + item.title() + "**" + ((item.trigger() != null) ? " (" + item.trigger() + ")" : ""));
				if (item.detail() != null || !link.isEmpty()) {
					add("  " + ((item.detail() != null) ? item.detail() : "") + link);
				}
				if (item.detect() != null) {
					add("  - 검색 레시피 " + code(item.detect()));
				}
			}
			add("");
		}
	}

	private void detected(List<StageResult.DetectedItem> detected) {
		if (detected.isEmpty()) {
			return;
		}
		section("## 검색 결과 (detect)",
				"레시피가 자동으로 바꾸지 않는 코드를 검색으로 찾은 위치. 문제인지는 판정하지 않음. 항목별 이유는 recipes 의 detect/manual-items.yml 주석 참고.");
		detected.forEach((item) -> add("- " + code(item.file()) + " 의 " + code(truncate(item.code(), 120))));
		add("");
	}

	private void heading(String heading) {
		add(heading, "");
	}

	private void section(String heading, String description) {
		add(heading, description, "");
	}

	private void row(String label, String value) {
		add("| " + label + " | " + value + " |");
	}

	private void add(String... text) {
		this.lines.addAll(List.of(text));
	}

	private static <T> Map<String, List<T>> groupBy(List<T> items, Function<T, String> key) {
		Map<String, List<T>> groups = new LinkedHashMap<>();
		items.forEach((item) -> groups.computeIfAbsent(key.apply(item), (group) -> new ArrayList<>()).add(item));
		return groups;
	}

	private static <T> Comparator<Map.Entry<String, List<T>>> largestFirst() {
		return Comparator.comparingInt((Map.Entry<String, List<T>> group) -> group.getValue().size()).reversed();
	}

	/**
	 * 인라인 코드. 값에 백틱이 있으면 더 긴 백틱으로 감싼다 (마크다운이 깨지지 않도록).
	 */
	static String code(String text) {
		String fence = text.contains("`") ? "``" : "`";
		String padding = (text.startsWith("`") || text.endsWith("`")) ? " " : "";
		return fence + padding + text + padding + fence;
	}

	private static String joinCode(Collection<String> items, int max) {
		List<String> list = new ArrayList<>(items);
		List<String> shown = list.subList(0, Math.min(max, list.size())).stream().map(ResultMarkdown::code).toList();
		return String.join(", ", shown) + ((list.size() > max) ? " 외 " + (list.size() - max) + "개" : "");
	}

	private static String simpleName(String className) {
		return className.substring(className.lastIndexOf('.') + 1);
	}

	private static String truncate(String text, int max) {
		return (text.length() > max) ? text.substring(0, max) : text;
	}

	private record ChecklistSection(Fix fix, String title) {
	}

}
