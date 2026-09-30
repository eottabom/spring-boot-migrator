package com.eottabom.migration.result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eottabom.migration.stage.StageTag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ResultHtmlTests {

	@TempDir
	Path dir;

	@Test
	void embedsStageResultsAndDiffsIntoOnePage() throws IOException {
		// 백슬래시, 한글, </script> 가 그대로 살아남아야 한다
		Path result = Files.writeString(this.dir.resolve("result.json"),
				"{\"stage\":\"3.4\",\"compile\":\"ok\",\"tests\":{\"total\":1,\"failures\":[]},"
						+ "\"detect\":[{\"file\":\"A.java\",\"code\":\"String p = \\\"C:\\\\\\\\tmp\\\"; // 한글 </script>\"}]}");
		Path patch = Files.writeString(this.dir.resolve("stage.patch"),
				"diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-a\n+b\n");
		Path html = this.dir.resolve("result.html");

		ResultHtml.write(html,
				new ResultHtml.Page("demo", "3.3.5", null,
						List.of(new ResultHtml.StageEntry(StageTag.parse("01-boot-3.4"), result, patch),
								new ResultHtml.StageEntry(StageTag.parse("02-java21"), this.dir.resolve("none.json"),
										this.dir.resolve("none.patch")))));

		String page = Files.readString(html);
		assertThat(page).doesNotContain("/*__DATA__*/").doesNotContain("한글 </script>");
		Matcher script = Pattern.compile("<script id=\"data\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL)
			.matcher(page);
		assertThat(script.find()).isTrue();
		JsonNode data = new ObjectMapper().readTree(script.group(1));
		assertThat(data.get("project").asText()).isEqualTo("demo");
		assertThat(data.get("startBoot").asText()).isEqualTo("3.3.5");
		assertThat(data.get("currentBoot").asText()).isEqualTo("?");
		JsonNode stages = data.get("stages");
		assertThat(stages.get(0).get("name").asText()).isEqualTo("3.4");
		assertThat(stages.get(0).at("/result/detect/0/code").asText())
			.isEqualTo("String p = \"C:\\\\tmp\"; // 한글 </script>");
		assertThat(stages.get(0).get("patch").asText()).contains("+b");
		assertThat(stages.get(1).get("result").isNull()).isTrue();
		assertThat(stages.get(1).get("patch").isNull()).isTrue();
	}

	@Test
	void truncatesHugePatchAtFileBoundary() throws IOException {
		String file = "diff --git a/A.java b/A.java\n@@ -1 +1 @@\n-" + "a".repeat(1000) + "\n+b\n";
		Path patch = Files.writeString(this.dir.resolve("stage.patch"), file.repeat(2000));
		Path html = this.dir.resolve("result.html");

		ResultHtml.write(html, new ResultHtml.Page("demo", null, null, List
			.of(new ResultHtml.StageEntry(StageTag.parse("01-boot-3.4"), this.dir.resolve("none.json"), patch))));

		Matcher script = Pattern.compile("<script id=\"data\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL)
			.matcher(Files.readString(html));
		assertThat(script.find()).isTrue();
		JsonNode stage = new ObjectMapper().readTree(script.group(1)).get("stages").get(0);
		assertThat(stage.get("patchTruncated").asBoolean()).isTrue();
		assertThat(stage.get("patch").asText()).endsWith("+b\n").hasSizeLessThan(1_500_001);
	}

}
