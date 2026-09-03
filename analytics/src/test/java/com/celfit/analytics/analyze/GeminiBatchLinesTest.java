package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * phase별 배치 JSONL 라인 조립 계약(2026-09-03 2단계 분리 설계 §4-4·§5).
 * 파트 A 라인은 캡션 전용 스키마·유저 텍스트를, 파트 B 라인은 해석 5필드 스키마와
 * 저장된 사실·지표·기준선을 싣는다. 사이드카 키는 kind와 무관하게 한 벌이다.
 */
class GeminiBatchLinesTest {

	ObjectMapper om = new ObjectMapper();

	private static Map<String, Object> row() {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("account_handle", "acct1");
		r.put("caption", "캡션A");
		r.put("content_type", "reels");
		r.put("views", 11000L);
		r.put("likes", 520L);
		r.put("comments", 52L);
		r.put("ad_marked", Boolean.TRUE);
		r.put("recent_reels_avg_views", 9000L);
		r.put("rank_in_recent_reels", 1);
		r.put("recent_reels_count", 2);
		r.put("recent_contents_count", 3);
		r.put("recent12_avg_engagement_rate", new java.math.BigDecimal("0.0496"));
		r.put("recent12_avg_like_count", 940L);
		r.put("recent12_avg_comment_count", 61L);
		r.put("category_top_percentile", 67);
		r.put("category_avg_views", 19333L);
		r.put("category_sample_size", 3L);
		r.put("timely", Boolean.TRUE);
		return r;
	}

	private String userTextOf(ObjectNode line) {
		return line.path("request").path("contents").get(0).path("parts").get(0).path("text").asString();
	}

	private String schemaOf(ObjectNode line) {
		return line.path("request").path("generationConfig").path("responseSchema").toString();
	}

	@Test
	void 파트A_라인은_캡션만_싣고_해석_스키마가_없다() {
		ObjectNode line = GeminiBatchLines.factsRequestLine(om, "post_a", row(), "SYSTEM_FACTS");

		assertEquals("post_a", line.path("key").asString());
		String user = userTextOf(line);
		assertTrue(user.startsWith("콘텐츠: post_a (@acct1, reels)"), user);
		assertTrue(user.contains("인스타 유료 파트너십 태그: 있음"));
		assertFalse(user.contains("지표:"));
		assertFalse(user.contains("계정 기준선:"));
		assertFalse(schemaOf(line).contains("aiContentSummary"));
		assertEquals("SYSTEM_FACTS", line.path("request").path("systemInstruction")
				.path("parts").get(0).path("text").asString());
	}

	@Test
	void 파트B_라인은_사실과_지표_기준선_댓글분포를_싣는다() {
		Map<String, Object> facts = Map.of("main_category", "cleansing", "ad_type", "sponsored");

		ObjectNode line = GeminiBatchLines.synthesisRequestLine(om, "post_a", row(),
				Map.of("purchase", 1L), facts, "SYSTEM_SYNTH");

		assertEquals("post_a", line.path("key").asString());
		String user = userTextOf(line);
		assertTrue(user.startsWith("콘텐츠: post_a (@acct1, reels)"), user);
		assertTrue(user.contains("확인된 사실:"));
		assertTrue(user.contains("cleansing"));
		assertTrue(user.contains("views=11000"));
		assertTrue(user.contains("purchase=1"));
		// 기준선은 화면과 같은 퍼센트 단위로 실린다(PromptBaseline 규약)
		assertTrue(user.contains("recent12_avg_engagement_rate_pct"));
		String schema = schemaOf(line);
		assertTrue(schema.contains("aiContentSummary"));
		assertFalse(schema.contains("detectedBrands"));
	}

	@Test
	void 사이드카_키는_kind와_무관하게_한_벌이다() {
		ObjectNode factsSidecar = GeminiBatchLines.sidecarLine(om, "post_a", row());

		for (String key : GeminiBatchLines.SIDECAR_KEYS) {
			assertTrue(factsSidecar.has(key), "사이드카에 " + key + " 누락");
		}
		assertEquals("post_a", factsSidecar.path("short_code").asString());
	}
}
