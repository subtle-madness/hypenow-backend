package com.celfit.analytics.analyze;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AccountBatchLinesTest {

	final ObjectMapper om = new ObjectMapper();

	@Test
	void 요청_라인은_key와_계정별_시스템_유저_텍스트_스키마를_담는다() {
		ObjectNode line = AccountBatchLines.requestLine(om, "beauty_kim", "시스템 지시문", "유저 텍스트");
		assertThat(line.path("key").asString()).isEqualTo("beauty_kim");
		assertThat(line.path("request").path("systemInstruction").path("parts").path(0)
				.path("text").asString()).isEqualTo("시스템 지시문");
		assertThat(line.path("request").path("contents").path(0).path("parts").path(0)
				.path("text").asString()).isEqualTo("유저 텍스트");
		JsonNode gen = line.path("request").path("generationConfig");
		assertThat(gen.path("responseMimeType").asString()).isEqualTo("application/json");
		assertThat(gen.path("maxOutputTokens").asInt())
				.isEqualTo(com.celfit.analytics.llm.GeminiAccountSynthesizer.MAX_OUTPUT_TOKENS);
	}

	@Test
	void 사이드카는_라운드트립되고_null_필드를_보존한다() {
		OffsetDateTime posted = OffsetDateTime.parse("2026-08-16T07:00:00+09:00");
		String jsonl = om.writeValueAsString(
				AccountBatchLines.sidecarLine(om, "a_handle", posted, 34L,
						com.celfit.analytics.llm.AdSituation.COMPARABLE.name())) + "\n"
				+ om.writeValueAsString(
				AccountBatchLines.sidecarLine(om, "b_handle", null, null,
						com.celfit.analytics.llm.AdSituation.NO_ADS.name())) + "\n";
		Map<String, Map<String, String>> parsed = AccountBatchLines.parseSidecar(om, jsonl);
		assertThat(parsed.get("a_handle").get("last_posted_at")).isEqualTo(posted.toString());
		assertThat(parsed.get("a_handle").get("analyzed_count")).isEqualTo("34");
		assertThat(parsed.get("a_handle").get("ad_situation")).isEqualTo("COMPARABLE");
		assertThat(parsed.get("b_handle").get("last_posted_at")).isNull();
		assertThat(parsed.get("b_handle").get("analyzed_count")).isNull();
	}

	@Test
	void Vertex_출력에_key가_없으면_에코된_유저_텍스트_첫_줄에서_핸들을_복원한다() {
		ObjectNode node = om.createObjectNode();
		node.putObject("request").putArray("contents").addObject().putArray("parts")
				.addObject().put("text", "계정: @beauty_kim (광고 활동: 비교 가능)\n계정 지표: {...}");
		assertThat(AccountBatchLines.handleFromEcho(node)).isEqualTo("beauty_kim");
	}
}
