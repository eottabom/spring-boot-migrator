package com.eottabom.migration.pipeline;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.plan.MigrationPlan;
import com.eottabom.migration.project.ProjectState;
import com.eottabom.migration.stage.StageId;
import com.eottabom.migration.stage.StageTag;
import com.eottabom.migration.workspace.MigrationWorkspace;
import org.jspecify.annotations.Nullable;

/**
 * history.md. 실행마다 헤더, stage 별 한 줄, 멈춤이나 완료를 남긴다.
 */
final class RunHistory {

	private final MigrationWorkspace ws;

	private final String projectName;

	RunHistory(MigrationWorkspace ws, String projectName) {
		this.ws = ws;
		this.projectName = projectName;
	}

	void header(ProjectState project, MigrationPlan plan, String summary, @Nullable String resumeNote) {
		StringBuilder header = new StringBuilder().append("## ")
			.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
			.append(" 실행\n\n| 시작 | 목표 | stage |\n|---|---|---|\n| Boot ")
			.append(project.bootVersion())
			.append(" / Gradle ")
			.append(RunnerConsole.orUnknown(project.gradleVersion()))
			.append(" / Java ")
			.append(RunnerConsole.orUnknown(project.lowestDeclaredJava()))
			.append(" | Boot ")
			.append(plan.targetBoot())
			.append((plan.targetJava() == null) ? "" : " / Java " + plan.targetJava())
			.append(" (")
			.append(summary)
			.append(") | ")
			.append(plan.stageNames())
			.append(" |\n\n");
		plan.notes().forEach((note) -> header.append("- ").append(note).append('\n'));
		if (resumeNote != null) {
			header.append(resumeNote).append('\n');
		}
		append(header.toString());
	}

	void stageTable() {
		append("\n| stage | 컴파일 | 테스트 | 빌드 | 변경 파일 | 검색 결과 | 체크리스트 | 결과 |\n|---|---|---|---|---|---|---|---|\n");
	}

	/** 멈춘 기록 뒤에 재개한 stage 의 한 줄이 표로 이어지도록 표 머리를 다시 쓴다 */
	void resuming(StageId stage) {
		append("재개: " + stage + " stage 의 게이트를 다시 확인\n");
		stageTable();
	}

	void note(String note) {
		append("- " + note + "\n");
	}

	void row(String row) {
		append(row);
	}

	void stopped(StageTag tag, String reason) {
		append("\n" + tag.stage() + " stage 에서 " + reason + "로 멈췄어요 (" + tag + "/result.md)\n\n");
	}

	void finished(@Nullable String startBoot, @Nullable String finalBoot) {
		append("\nBoot " + startBoot + " → " + finalBoot + " 완료\n\n");
	}

	private void append(String text) {
		this.ws.appendHistory(this.projectName, text);
	}

}
