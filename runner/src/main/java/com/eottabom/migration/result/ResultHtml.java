package com.eottabom.migration.result;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.eottabom.migration.misc.AtomicFiles;
import com.eottabom.migration.misc.TextFiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * 전 stage 를 한 페이지로 보는 result.html. 외부 리소스 없이 stage 별 result.json 과 stage.patch 를 모은다.
 */
public final class ResultHtml {

	/** stage diff 를 페이지에 넣는 최대 크기. 넘으면 앞부분만 넣고 stage.patch 를 보라고 표시한다 */
	private static final int MAX_PATCH_CHARS = 1_500_000;

	private ResultHtml() {
	}

	public static void write(Path out, Page page) {
		ObjectNode data = ResultJson.mapper().createObjectNode();
		data.put("project", page.project());
		data.put("startBoot", (page.startBoot() != null) ? page.startBoot() : "?");
		data.put("currentBoot", (page.currentBoot() != null) ? page.currentBoot() : "?");
		data.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
		ArrayNode stages = data.putArray("stages");
		for (StageEntry entry : page.stages()) {
			ObjectNode stage = stages.addObject();
			stage.put("tag", entry.tag());
			stage.put("name", entry.name());
			stage.set("result", Files.exists(entry.resultJson()) ? ResultJson.read(entry.resultJson()) : null);
			String patch = Files.exists(entry.stagePatch()) ? TextFiles.read(entry.stagePatch()) : null;
			boolean truncated = patch != null && patch.length() > MAX_PATCH_CHARS;
			stage.put("patch", (patch != null && truncated) ? truncate(patch) : patch);
			stage.put("patchTruncated", truncated);
		}
		try {
			// </script> 가 데이터 안에 있어도 스크립트 태그가 끝나지 않도록
			String json = ResultJson.mapper().writeValueAsString(data).replace("</", "<\\/");
			AtomicFiles.write(out, template().replace("/*__DATA__*/", json));
		}
		catch (JsonProcessingException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String template() {
		try (InputStream in = ResultHtml.class.getResourceAsStream("/com/eottabom/migration/result-template.html")) {
			if (in == null) {
				throw new IllegalStateException("result-template.html 이 없어요");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 마지막으로 온전한 파일 diff 까지만 둔다 */
	private static String truncate(String patch) {
		return patch.substring(0, patch.lastIndexOf("\ndiff --git", MAX_PATCH_CHARS) + 1);
	}

	/**
	 * @param startBoot 실행을 시작할 때의 Boot (모르면 null)
	 * @param stages 지난 실행까지 포함한 stage (번호 순서)
	 */
	public record Page(String project, @Nullable String startBoot, @Nullable String currentBoot,
			List<StageEntry> stages) {
	}

	/**
	 * @param resultJson 없으면 결과 없이 stage 만 보여 준다
	 * @param stagePatch 없으면 diff 없이 보여 준다
	 */
	public record StageEntry(String tag, String name, Path resultJson, Path stagePatch) {
	}

}
