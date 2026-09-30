package com.eottabom.migration.pipeline.step;

import java.util.List;

import com.eottabom.migration.console.RunnerConsole;
import com.eottabom.migration.result.ResultHtml;
import com.eottabom.migration.result.StageResult;
import com.eottabom.migration.result.StageSummary;
import com.eottabom.migration.workspace.MigrationWorkspace;
import com.eottabom.migration.workspace.StageFiles;
import org.jspecify.annotations.Nullable;

/**
 * stage 결과(result.md, result.json)와 전 stage 를 모은 result.html 을 쓴다.
 */
public record RecordStep(RunnerConsole console) {

	public StageSummary write(StageResult result, StageFiles files) {
		result.write(files.resultMarkdown(), files.resultJson());
		StageSummary summary = result.summary();
		summary.table().forEach(this.console::line);
		this.console.line("   결과: {}", files.resultMarkdown());
		return summary;
	}

	/**
	 * 지금까지의 stage 를 한 페이지로 모은다. stage.patch 를 만든 뒤에 부른다 (변경 보기가 지난 시도의 patch 를 읽지 않도록).
	 * @param startBoot 실행을 시작할 때의 Boot (모르면 null)
	 * @param currentBoot 지금 Boot (모르면 null)
	 */
	public void html(MigrationWorkspace ws, String projectName, @Nullable String startBoot,
			@Nullable String currentBoot) {
		List<ResultHtml.StageEntry> stages = ws.stageTags()
			.stream()
			.map((tag) -> new ResultHtml.StageEntry(tag, ws.stage(tag).resultJson(), ws.stage(tag).stagePatch()))
			.toList();
		ResultHtml.write(ws.resultHtml(), new ResultHtml.Page(projectName, startBoot, currentBoot, stages));
		this.console.line("   result.html: {}", ws.resultHtml().toUri());
	}

}
