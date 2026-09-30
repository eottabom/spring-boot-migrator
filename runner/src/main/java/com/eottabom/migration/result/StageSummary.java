package com.eottabom.migration.result;

import java.util.ArrayList;
import java.util.List;

import com.eottabom.migration.guide.ChecklistItem.Fix;

/**
 * stage 결과 상단의 요약 표. 마크다운 표, history.md 의 stage 한 줄, 콘솔 출력이 모두 이 모델에서 나온다.
 */
public record StageSummary(List<Row> rows) {

	private static final String COMPILE = "컴파일";

	private static final String TESTS = "테스트";

	private static final String BUILD = "빌드";

	private static final String DETECTED = "검색 결과 (detect)";

	private static final String CHECKLIST = "체크리스트";

	private static final String CHANGED_FILES = "자동 변경 파일";

	static StageSummary of(StageResult result) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Row(COMPILE, outcome(result.compile(), "❌ 실패")));
		rows.add(new Row(TESTS, testResult(result)));
		rows.add(new Row(BUILD,
				outcome(result.build(),
						result.buildFailureExisting() ? "❌ 실패 (원본에서도 실패하던 태스크만 실패한 기존 문제, start/baseline-build.log)"
								: "❌ 실패 (테스트 외 태스크, 패키징이나 asciidoctor, checkstyle 등. build.log 참고)")));
		rows.add(new Row("제거 예정 API 사용 ([removal])", result.warnings().removal().size() + " 종류"));
		rows.add(new Row("deprecated API 사용", result.warnings().deprecation().size() + " 종류"));
		if (!result.deprecationFixes().isEmpty()) {
			rows.add(new Row("deprecated API 대체 레시피", result.deprecationFixes().size() + " 개"));
		}
		rows.add(new Row("설정 키 변경 (properties-migrator)", "이름 변경 " + result.properties().renamed().size() + " / 지원 중단 "
				+ result.properties().unsupported().size()));
		rows.add(new Row(DETECTED, result.detect().size() + " 곳"));
		if (!result.checklist().isEmpty()) {
			rows.add(new Row(CHECKLIST, "사람 " + countFix(result, Fix.MANUAL) + " / 확인 " + countFix(result, Fix.ASSISTED)
					+ " / 자동 " + countFix(result, Fix.AUTO)));
		}
		rows.add(new Row(CHANGED_FILES,
				result.changes().files().size() + " 개 (custom 레시피 " + result.changes().custom().size() + " 종)"));
		return new StageSummary(List.copyOf(rows));
	}

	/** 마크다운 표 */
	public List<String> table() {
		List<String> table = new ArrayList<>(List.of("| 항목 | 결과 |", "|---|---|"));
		this.rows.forEach((row) -> table.add("| " + row.label() + " | " + row.value() + " |"));
		return table;
	}

	/** history.md 의 stage 한 줄 */
	public String historyRow(String stage, String tag) {
		return "| " + stage + " | " + value(COMPILE) + " | " + value(TESTS) + " | " + value(BUILD) + " | "
				+ value(CHANGED_FILES) + " | " + value(DETECTED) + " | " + value(CHECKLIST) + " | [" + tag + "](" + tag
				+ "/result.md) |\n";
	}

	private String value(String label) {
		return this.rows.stream().filter((row) -> row.label().equals(label)).map(Row::value).findFirst().orElse("-");
	}

	private static String testResult(StageResult result) {
		int total = result.tests().total();
		if (total == 0) {
			return "실행 안 함";
		}
		int existing = result.existingFailures().size();
		int flaky = result.tests().flaky().size();
		String note = ((existing > 0) ? " (원본에서도 실패하던 " + existing + "개 제외)" : "")
				+ ((flaky > 0) ? " (다시 돌려 통과한 " + flaky + "개 포함)" : "");
		int failed = result.newFailures().size();
		return (failed > 0) ? "❌ " + failed + " / " + total + " 실패" + note : "✅ " + (total - existing) + "개 통과" + note;
	}

	private static long countFix(StageResult result, Fix fix) {
		return result.checklist().stream().filter((item) -> item.fix() == fix).count();
	}

	private static String outcome(Outcome outcome, String failed) {
		return switch (outcome) {
			case PASSED -> "✅ 통과";
			case FAILED -> failed;
			case SKIPPED -> "실행 안 함";
		};
	}

	record Row(String label, String value) {
	}

}
