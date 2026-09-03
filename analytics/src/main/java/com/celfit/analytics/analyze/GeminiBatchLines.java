package com.celfit.analytics.analyze;

import com.celfit.analytics.llm.BeautyTaxonomy;
import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.ContentInsightPort.ContentInsight;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.ContentToSynthesize;
import com.celfit.analytics.llm.GeminiContentAnalyzer;
import com.celfit.analytics.llm.GeminiContentSynthesizer;
import com.celfit.analytics.llm.Synthesis;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini/Vertex Batch API JSONL 라인 조립·결과 라인 해석 공용 헬퍼 — {@link GeminiBackfillRunner}
 * (초기 백필 one-shot)와 야간 배치 전송 경로({@link ContentAnalysisJob}·{@link ContentBatchCollectJob},
 * 2026-08-11)가 공유한다. 원래 GeminiBackfillRunner 인스턴스 메서드였던 requestLine/sidecarLine·
 * collect() 본문을 그대로 옮긴 것 — 백필 러너의 기존 동작·테스트는 변경 없음.
 */
final class GeminiBatchLines {

	private static final Logger log = LoggerFactory.getLogger(GeminiBatchLines.class);

	/** 사이드카 라인에 싣는 키 — 기준선 스냅샷 + caption(속성 폐기 판정) + timely(V33 마킹). */
	static final List<String> SIDECAR_KEYS = List.of(
			"recent_reels_avg_views", "rank_in_recent_reels", "recent_reels_count",
			"recent_contents_count", "recent12_avg_engagement_rate", "recent12_avg_like_count",
			"recent12_avg_comment_count", "category_top_percentile", "category_avg_views",
			"category_sample_size", "caption", "timely");

	private static final java.util.regex.Pattern ECHO_SHORT_CODE =
			java.util.regex.Pattern.compile("^콘텐츠: (\\S+) \\(");

	private GeminiBatchLines() {
	}

	/**
	 * JSONL 요청 라인 — key=short_code, request=GenerateContentRequest(camelCase — proto JSON은
	 * 양쪽 표기를 다 받지만 AI Studio·Vertex 공용으로 통일).
	 *
	 * @param commentCategoryCounts 댓글 분류 분포(ai_category → 건수) — GeminiContentAnalyzer의
	 *        시스템 프롬프트(aiCommentInsight 근거)·유저 텍스트가 요구하는 실입력이다. 호출자가
	 *        직접 넘긴다(2026-08-11 리뷰 반영 — 이전엔 이 메서드 내부에서 Map.of()로 항상 비웠다).
	 */
	static ObjectNode requestLine(ObjectMapper om, String shortCode, Map<String, Object> r,
			Map<String, Long> commentCategoryCounts, String system) {
		Map<String, Object> baseline = PromptBaseline.ofRow(r);
		ContentToAnalyze content = new ContentToAnalyze(shortCode, (String) r.get("account_handle"),
				(String) r.get("caption"), (String) r.get("content_type"),
				numberOf(r.get("views")), numberOf(r.get("likes")), numberOf(r.get("comments")),
				baseline, commentCategoryCounts, (Boolean) r.get("ad_marked"));
		ObjectNode line = om.createObjectNode();
		line.put("key", shortCode);
		ObjectNode request = line.putObject("request");
		request.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
		request.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", GeminiContentAnalyzer.userText(content));
		ObjectNode gen = request.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", om.readTree(GeminiContentAnalyzer.RESPONSE_SCHEMA));
		gen.put("maxOutputTokens", GeminiContentAnalyzer.MAX_OUTPUT_TOKENS);
		return line;
	}

	/**
	 * 파트 A(사실) 요청 라인 - 캡션과 유료 파트너십 태그만 싣는다(2026-09-03 2단계 분리 §4-4).
	 * 지표·기준선·댓글 분포를 안 실으므로 성숙(D+4)을 기다릴 필요가 없다.
	 * 응답 스키마는 해석 5필드가 빠진 {@code RESPONSE_SCHEMA_FACTS}다.
	 */
	static ObjectNode factsRequestLine(ObjectMapper om, String shortCode, Map<String, Object> r,
			String system) {
		ContentToAnalyze content = new ContentToAnalyze(shortCode, (String) r.get("account_handle"),
				(String) r.get("caption"), (String) r.get("content_type"),
				null, null, null, Map.of(), Map.of(), (Boolean) r.get("ad_marked"));
		ObjectNode line = om.createObjectNode();
		line.put("key", shortCode);
		ObjectNode request = line.putObject("request");
		request.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
		request.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", GeminiContentAnalyzer.userTextFacts(content));
		ObjectNode gen = request.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", om.readTree(GeminiContentAnalyzer.RESPONSE_SCHEMA_FACTS));
		gen.put("maxOutputTokens", GeminiContentAnalyzer.MAX_OUTPUT_TOKENS);
		return line;
	}

	/**
	 * 파트 B(해석) 요청 라인 - 저장된 사실 + 핀 지표 + 기준선 스냅샷 + 댓글 분포.
	 * 프롬프트·스키마는 온라인 재생성 경로({@link GeminiContentSynthesizer})와 같은 것을 쓴다 -
	 * 복제하면 배치 생성분과 재생성분의 문구 의미가 갈린다(07-21 사고와 같은 함정).
	 *
	 * @param facts {@code StoredFacts.of}가 만든 "확인된 사실" 9키.
	 */
	static ObjectNode synthesisRequestLine(ObjectMapper om, String shortCode, Map<String, Object> r,
			Map<String, Long> commentCategoryCounts, Map<String, Object> facts, String system) {
		ContentToSynthesize content = new ContentToSynthesize(shortCode,
				(String) r.get("account_handle"), (String) r.get("content_type"),
				numberOf(r.get("views")), numberOf(r.get("likes")), numberOf(r.get("comments")),
				PromptBaseline.ofRow(r), commentCategoryCounts, facts);
		ObjectNode line = om.createObjectNode();
		line.put("key", shortCode);
		ObjectNode request = line.putObject("request");
		request.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
		request.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", GeminiContentSynthesizer.userText(content));
		ObjectNode gen = request.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", om.readTree(GeminiContentSynthesizer.RESPONSE_SCHEMA));
		gen.put("maxOutputTokens", GeminiContentSynthesizer.MAX_OUTPUT_TOKENS);
		return line;
	}

	/** 사이드카 라인 — 프롬프트에 실은 기준선 스냅샷을 저장 시점에 그대로 복원하기 위한 기록. */
	static ObjectNode sidecarLine(ObjectMapper om, String shortCode, Map<String, Object> r) {
		ObjectNode line = om.createObjectNode();
		line.put("short_code", shortCode);
		for (String k : SIDECAR_KEYS) {
			Object v = r.get(k);
			if (v == null) {
				line.putNull(k);
			} else {
				line.put(k, v.toString());
			}
		}
		return line;
	}

	/** 사이드카 파일 읽기(백필 CLI 전용) — 없으면 예외(제출을 먼저 해야 함). */
	static Map<String, Map<String, String>> readSidecar(ObjectMapper om, Path sidecarPath) {
		String contents;
		try {
			contents = Files.readString(sidecarPath);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("사이드카 없음 — 제출을 먼저 실행: " + sidecarPath, e);
		}
		return parseSidecar(om, contents);
	}

	/**
	 * 사이드카 JSONL 문자열 파싱 — 백필 CLI(파일)·상시 배치(content_batch_jobs.sidecar_jsonl 컬럼,
	 * 2026-08-11)가 공유한다. 상시 배치는 analytics 컨테이너에 쓰기 가능한 볼륨이 없어 로컬 파일
	 * 대신 DB 컬럼에 저장하므로, 파싱 로직만 문자열 단위로 분리해 둔다.
	 */
	static Map<String, Map<String, String>> parseSidecar(ObjectMapper om, String contents) {
		Map<String, Map<String, String>> out = new LinkedHashMap<>();
		for (String line : contents.split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node = om.readTree(line);
			Map<String, String> vals = new LinkedHashMap<>();
			for (String k : SIDECAR_KEYS) {
				JsonNode v = node.path(k);
				vals.put(k, v.isNull() || v.isMissingNode() ? null : v.asString());
			}
			out.put(node.path("short_code").asString(), vals);
		}
		return out;
	}

	static Baseline baselineOf(Map<String, String> b) {
		return new Baseline(longOrNull(b.get("recent_reels_avg_views")),
				intOrNull(b.get("rank_in_recent_reels")), intOrNull(b.get("recent_reels_count")),
				intOrNull(b.get("recent_contents_count")), decimalOrNull(b.get("recent12_avg_engagement_rate")),
				longOrNull(b.get("recent12_avg_like_count")), longOrNull(b.get("recent12_avg_comment_count")),
				intOrNull(b.get("category_top_percentile")), longOrNull(b.get("category_avg_views")),
				longOrNull(b.get("category_sample_size")));
	}

	/** 배치 응답의 state — metadata.state(Vertex 표준)·state(구버전 표기) 둘 다 대응. */
	static String state(JsonNode batch) {
		return firstNonNull(text(batch, "metadata", "state"), text(batch, "state"));
	}

	/** 결과 파일 위치 — AI Studio·Vertex 응답 형태가 갈려 후보 경로를 순서대로 시도한다. */
	static String resultFileOf(JsonNode batch) {
		String resultFile = firstNonNull(
				text(batch, "metadata", "output", "responsesFile"),
				text(batch, "response", "responsesFile"),
				text(batch, "dest", "fileName"),
				text(batch, "metadata", "dest", "fileName"),
				text(batch, "outputInfo", "gcsOutputDirectory"));
		if (resultFile == null) {
			throw new IllegalStateException("결과 파일 이름을 찾지 못함 — 배치 응답: " + batch);
		}
		return resultFile;
	}

	/**
	 * 결과 한 줄 처리: 파싱 → sanitize → ON CONFLICT DO NOTHING INSERT(재실행 멱등).
	 * 마킹은 사이드카에 실린 뷰의 timely 판정을 승계(07-20 개정 — 구버전 사이드카는 late_backfill 폴백).
	 *
	 * @return true=저장 성공, false=실패(파싱 불가·사이드카 부재·빈 종합 등 — 다음 실행이 재대상 흡수)
	 */
	static boolean processResultLine(JdbcTemplate analysis, ObjectMapper om, String line,
			Map<String, Map<String, String>> sidecar, String model, BeautyTaxonomy taxonomy) {
		try {
			JsonNode node = om.readTree(line);
			String vertexStatus = node.path("status").asString("");
			if (!vertexStatus.isEmpty()) {
				log.warn("배치 실패 라인 (status={}): {}", vertexStatus, abbreviate(line));
				return false;
			}
			String shortCode = node.path("key").asString("");
			if (shortCode.isEmpty()) {
				shortCode = shortCodeFromEcho(node);
			}
			JsonNode text = node.path("response").path("candidates").path(0)
					.path("content").path("parts").path(0).path("text");
			if (shortCode.isEmpty() || text.isMissingNode()) {
				log.warn("결과 라인 해석 불가/오류 응답: {}", abbreviate(line));
				return false;
			}
			ContentInsight insight = GeminiContentAnalyzer.parse(om, text.asString(), taxonomy);
			if (insight.synthesis().aiContentSummary() == null
					|| insight.synthesis().aiContentSummary().isBlank()) {
				return false;
			}
			Map<String, String> base = sidecar.get(shortCode);
			if (base == null) {
				log.warn("사이드카에 없는 key: {}", shortCode);
				return false;
			}
			boolean hasCaption = base.get("caption") != null && !base.get("caption").isBlank();
			boolean timely = "true".equals(base.get("timely"));
			ContentAnalysisWriter.insert(analysis, om, shortCode, model, baselineOf(base),
					hasCaption ? insight.attributes() : null, insight.synthesis(), true,
					timely ? "timely" : "late_backfill");
			return true;
		} catch (Exception e) {
			log.warn("결과 라인 저장 실패: {}", abbreviate(line), e);
			return false;
		}
	}

	/** 결과 라인에서 (short_code, 응답 텍스트)를 꺼낸다 - 실패면 null. kind 3종이 공유. */
	private static String[] shortCodeAndText(ObjectMapper om, String line) {
		JsonNode node = om.readTree(line);
		String vertexStatus = node.path("status").asString("");
		if (!vertexStatus.isEmpty()) {
			log.warn("배치 실패 라인 (status={}): {}", vertexStatus, abbreviate(line));
			return null;
		}
		String shortCode = node.path("key").asString("");
		if (shortCode.isEmpty()) {
			shortCode = shortCodeFromEcho(node);
		}
		JsonNode text = node.path("response").path("candidates").path(0)
				.path("content").path("parts").path(0).path("text");
		if (shortCode.isEmpty() || text.isMissingNode()) {
			log.warn("결과 라인 해석 불가/오류 응답: {}", abbreviate(line));
			return null;
		}
		return new String[] {shortCode, text.asString()};
	}

	/**
	 * 파트 A 결과 한 줄 처리: 파싱 → sanitize → ON CONFLICT DO NOTHING INSERT(pending).
	 * 분류 대상인데 대분류를 못 얻은 경우는 통합 경로와 같은 처방으로 미분류 종결한다
	 * (temperature 0 결정론이라 재대상해도 같은 결과 - 무한 재시도 방지).
	 *
	 * @return true=저장 성공, false=실패(다음 실행이 재대상 흡수)
	 */
	static boolean processFactsResultLine(JdbcTemplate analysis, ObjectMapper om, String line,
			Map<String, Map<String, String>> sidecar, String model, BeautyTaxonomy taxonomy) {
		try {
			String[] parsed = shortCodeAndText(om, line);
			if (parsed == null) {
				return false;
			}
			String shortCode = parsed[0];
			Map<String, String> base = sidecar.get(shortCode);
			if (base == null) {
				log.warn("사이드카에 없는 key: {}", shortCode);
				return false;
			}
			boolean hasCaption = base.get("caption") != null && !base.get("caption").isBlank();
			ContentAttributes attrs = GeminiContentAnalyzer.parseFacts(om, parsed[1], taxonomy);
			if (Boolean.TRUE.equals(attrs.isRelevant()) && attrs.mainCategory() == null) {
				log.info("분류 대상이나 대분류 미도출 - 미분류로 종결 저장(재시도 루프 방지): {}", shortCode);
				attrs = attrs.asUnclassified();
			}
			ContentAnalysisWriter.insertFacts(analysis, om, shortCode, model, hasCaption ? attrs : null);
			return true;
		} catch (Exception e) {
			log.warn("파트 A 결과 라인 저장 실패: {}", abbreviate(line), e);
			return false;
		}
	}

	/**
	 * 파트 B 결과 한 줄 처리: 파싱 → 해석 5필드 + 기준선 스냅샷 UPDATE + 시점 확정.
	 * 마킹은 사이드카에 실린 뷰의 timely 판정을 승계한다(제출 시점 고정 - 수거 시점 재계산 금지).
	 *
	 * @return true=갱신 성공, false=실패 또는 0행(그 사이 행이 사라짐)
	 */
	static boolean processSynthesisResultLine(JdbcTemplate analysis, ObjectMapper om, String line,
			Map<String, Map<String, String>> sidecar, String model) {
		try {
			String[] parsed = shortCodeAndText(om, line);
			if (parsed == null) {
				return false;
			}
			String shortCode = parsed[0];
			Map<String, String> base = sidecar.get(shortCode);
			if (base == null) {
				log.warn("사이드카에 없는 key: {}", shortCode);
				return false;
			}
			Synthesis s = GeminiContentAnalyzer.parseSynthesis(om, parsed[1]);
			// 빈 종합은 저장하지 않는다 - 저장하면 pending이 풀려 다시 대상이 되지 않는다
			if (s.aiContentSummary() == null || s.aiContentSummary().isBlank()) {
				log.warn("해석 문구가 비어 있음 - 저장하지 않음(다음 실행 재대상): {}", shortCode);
				return false;
			}
			boolean timely = "true".equals(base.get("timely"));
			int updated = ContentAnalysisWriter.updateSynthesis(analysis, shortCode, model,
					baselineOf(base), s, timely ? "timely" : "late_backfill");
			if (updated == 0) {
				log.warn("해석 UPDATE 0행 - 제출~수거 사이에 행이 사라짐: {}", shortCode);
				return false;
			}
			return true;
		} catch (Exception e) {
			log.warn("파트 B 결과 라인 저장 실패: {}", abbreviate(line), e);
			return false;
		}
	}

	/** Vertex 출력엔 key가 없다 — 에코된 request의 유저 텍스트 첫 줄(콘텐츠: {shortCode} ()에서 복원. */
	static String shortCodeFromEcho(JsonNode node) {
		JsonNode parts = node.path("request").path("contents").path(0).path("parts");
		for (JsonNode part : parts) {
			String text = part.path("text").asString("");
			java.util.regex.Matcher m = ECHO_SHORT_CODE.matcher(text);
			if (m.find()) {
				return m.group(1);
			}
		}
		return "";
	}

	static Long numberOf(Object v) {
		return v == null ? null : ((Number) v).longValue();
	}

	private static Long longOrNull(String v) {
		return v == null ? null : new BigDecimal(v).longValue();
	}

	private static Integer intOrNull(String v) {
		return v == null ? null : new BigDecimal(v).intValue();
	}

	private static BigDecimal decimalOrNull(String v) {
		return v == null ? null : new BigDecimal(v);
	}

	private static String text(JsonNode root, String... path) {
		JsonNode n = root;
		for (String p : path) {
			n = n.path(p);
		}
		return n.isMissingNode() || n.isNull() ? null : n.asString();
	}

	private static String firstNonNull(String... vals) {
		for (String v : vals) {
			if (v != null && !v.isEmpty()) {
				return v;
			}
		}
		return null;
	}

	static String abbreviate(String s) {
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}
}
