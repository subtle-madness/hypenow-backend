package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.BeautyTaxonomy;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.ContentInsightPort.ContentInsight;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.llm.GeminiContentAnalyzer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 초기 백필 one-shot — 유료 키(GEMINI_API_KEY_PAID) Batch API 일회 실행 (2026-07-18 확정, ~$9).
 * submit: v_analysis_candidates ∩ v_analysis_baseline 중 미분석 전량 → JSONL 업로드 → 배치 생성 →
 *         사이드카(프롬프트에 실은 기준선 스냅샷) 저장. 캡션 단독(썸네일 미첨부 — 서명 URL 대부분 만료).
 * collect: 상태 확인 → 결과 다운로드 → 파싱·sanitize → ON CONFLICT DO NOTHING INSERT(재실행 멱등).
 * 실행(신 스키마 뷰 04·03 적용 후): --spring.main.web-application-type=none
 *   --analytics.backfill-submit=true → 로그의 배치 이름으로 --analytics.backfill-collect=batches/NNN
 */
public class GeminiBackfillRunner {

	private static final Logger log = LoggerFactory.getLogger(GeminiBackfillRunner.class);
	private static final List<String> SIDECAR_KEYS = List.of(
			"recent_reels_avg_views", "rank_in_recent_reels", "recent_reels_count",
			"recent_contents_count", "recent12_avg_engagement_rate", "recent12_avg_like_count",
			"recent12_avg_comment_count", "category_top_percentile", "category_avg_views",
			"category_sample_size", "caption");

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final GeminiBatchApi api;
	private final AnalyticsSettings settings;
	private final BeautyTaxonomyLoader taxonomyLoader;
	private final Path workDir;
	private final ObjectMapper om = new ObjectMapper();

	public GeminiBackfillRunner(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			GeminiBatchApi api, AnalyticsSettings settings, BeautyTaxonomyLoader taxonomyLoader,
			Path workDir) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.api = api;
		this.settings = settings;
		this.taxonomyLoader = taxonomyLoader;
		this.workDir = workDir;
	}

	/** @return 배치 잡 이름 (collect 실행 시 그대로 넘긴다). 대상 없으면 null. */
	public String submit() {
		Set<String> analyzed = new HashSet<>(analysis.queryForList(
				"SELECT short_code FROM content_analyses", String.class));
		// 기준선(최근 N개 윈도우) 정의 가능한 후보만 — 일상 잡의 전제와 동일 (윈도우 밖은 분석 대상 아님)
		List<Map<String, Object>> rows = raw.queryForList("""
				SELECT c.short_code, c.account_handle, c.content_type, c.caption,
				       c.views, c.likes, c.comments,
				       b.recent_reels_avg_views, b.rank_in_recent_reels, b.recent_reels_count,
				       b.recent_contents_count, b.recent12_avg_engagement_rate,
				       b.recent12_avg_like_count, b.recent12_avg_comment_count,
				       b.category_top_percentile, b.category_avg_views, b.category_sample_size
				FROM analytics.v_analysis_candidates c
				JOIN analytics.v_analysis_baseline b USING (short_code)
				ORDER BY c.short_code""");
		String system = GeminiContentAnalyzer.instructions(taxonomyLoader.get());
		StringBuilder jsonl = new StringBuilder();
		StringBuilder sidecar = new StringBuilder();
		int count = 0;
		for (Map<String, Object> r : rows) {
			String shortCode = (String) r.get("short_code");
			if (analyzed.contains(shortCode)) {
				continue;
			}
			jsonl.append(om.writeValueAsString(requestLine(shortCode, r, system))).append('\n');
			sidecar.append(om.writeValueAsString(sidecarLine(shortCode, r))).append('\n');
			count++;
		}
		if (count == 0) {
			log.info("백필 대상 없음");
			return null;
		}
		try {
			Files.createDirectories(workDir);
			Files.writeString(workDir.resolve("backfill-input.jsonl"), jsonl.toString());
			Files.writeString(workDir.resolve("backfill-sidecar.jsonl"), sidecar.toString());
		} catch (java.io.IOException e) {
			throw new IllegalStateException("백필 작업 파일 저장 실패: " + workDir, e);
		}
		String fileName = api.uploadFile(jsonl.toString().getBytes(StandardCharsets.UTF_8),
				"hypenow-backfill");
		String batchName = api.createBatch(settings.geminiModel(), fileName, "hypenow-backfill");
		log.info("백필 배치 제출 완료 — {}건, 배치: {} (수거: --analytics.backfill-collect={})",
				count, batchName, batchName);
		return batchName;
	}

	/** JSONL 요청 라인 — key=short_code, request=GenerateContentRequest(문서 형식 snake_case). */
	ObjectNode requestLine(String shortCode, Map<String, Object> r, String system) {
		Map<String, Object> baseline = new LinkedHashMap<>();
		baseline.put("recent_reels_avg_views", r.get("recent_reels_avg_views"));
		baseline.put("rank_in_recent_reels", r.get("rank_in_recent_reels"));
		baseline.put("recent_contents_count", r.get("recent_contents_count"));
		baseline.put("recent12_avg_engagement_rate", r.get("recent12_avg_engagement_rate"));
		baseline.put("recent12_avg_like_count", r.get("recent12_avg_like_count"));
		baseline.put("recent12_avg_comment_count", r.get("recent12_avg_comment_count"));
		baseline.put("category_top_percentile", r.get("category_top_percentile"));
		ContentToAnalyze content = new ContentToAnalyze(shortCode, (String) r.get("account_handle"),
				(String) r.get("caption"), (String) r.get("content_type"),
				numberOf(r.get("views")), numberOf(r.get("likes")), numberOf(r.get("comments")),
				baseline, Map.of());
		ObjectNode line = om.createObjectNode();
		line.put("key", shortCode);
		ObjectNode request = line.putObject("request");
		request.putObject("system_instruction").putArray("parts").addObject().put("text", system);
		request.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", GeminiContentAnalyzer.userText(content));
		ObjectNode gen = request.putObject("generation_config");
		gen.put("temperature", 0);
		gen.put("response_mime_type", "application/json");
		gen.set("response_schema", om.readTree(GeminiContentAnalyzer.RESPONSE_SCHEMA));
		gen.put("max_output_tokens", GeminiContentAnalyzer.MAX_OUTPUT_TOKENS);
		return line;
	}

	/** 사이드카 라인 — 프롬프트에 실은 기준선 스냅샷을 저장 시점에 그대로 복원하기 위한 기록. */
	ObjectNode sidecarLine(String shortCode, Map<String, Object> r) {
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

	/** @return 저장 건수. 배치 미완료면 -1 (상태 로그만 — 나중에 다시 실행). */
	public int collect(String batchName) {
		JsonNode batch = om.readTree(api.getBatch(batchName));
		String state = firstNonNull(text(batch, "metadata", "state"), text(batch, "state"));
		if (state == null || !state.endsWith("_SUCCEEDED")) {
			log.info("배치 미완료 — state={} (응답: {})", state, abbreviate(batch.toString()));
			return -1;
		}
		String resultFile = firstNonNull(
				text(batch, "metadata", "output", "responsesFile"),
				text(batch, "response", "responsesFile"),
				text(batch, "dest", "fileName"),
				text(batch, "metadata", "dest", "fileName"));
		if (resultFile == null) {
			throw new IllegalStateException("결과 파일 이름을 찾지 못함 — 배치 응답: " + batch);
		}
		Map<String, Map<String, String>> sidecar = readSidecar();
		String model = settings.geminiModel();
		BeautyTaxonomy taxonomy = taxonomyLoader.get();
		int saved = 0;
		int failed = 0;
		for (String line : api.downloadFile(resultFile).split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			try {
				JsonNode node = om.readTree(line);
				String shortCode = node.path("key").asString("");
				JsonNode text = node.path("response").path("candidates").path(0)
						.path("content").path("parts").path(0).path("text");
				if (shortCode.isEmpty() || text.isMissingNode()) {
					failed++;
					log.warn("결과 라인 해석 불가/오류 응답: {}", abbreviate(line));
					continue;
				}
				ContentInsight insight = GeminiContentAnalyzer.parse(om, text.asString(), taxonomy);
				if (insight.synthesis().aiContentSummary() == null
						|| insight.synthesis().aiContentSummary().isBlank()) {
					failed++;
					continue;
				}
				Map<String, String> base = sidecar.get(shortCode);
				if (base == null) {
					failed++;
					log.warn("사이드카에 없는 key: {}", shortCode);
					continue;
				}
				boolean hasCaption = base.get("caption") != null && !base.get("caption").isBlank();
				// 백필 대상은 정의상 늦크롤(+pin+slack 이후 지표) — late_backfill 마킹 (V33).
				ContentAnalysisWriter.insert(analysis, om, shortCode, model, baselineOf(base),
						hasCaption ? insight.attributes() : null, insight.synthesis(), true, "late_backfill");
				saved++;
			} catch (Exception e) {
				failed++;
				log.warn("결과 라인 저장 실패: {}", abbreviate(line), e);
			}
		}
		log.info("백필 저장 완료 — {}건 저장, {}건 실패(잔여는 일상 파이프라인이 흡수)", saved, failed);
		return saved;
	}

	private Map<String, Map<String, String>> readSidecar() {
		Path path = workDir.resolve("backfill-sidecar.jsonl");
		String contents;
		try {
			contents = Files.readString(path);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("사이드카 없음 — submit을 먼저 실행: " + path, e);
		}
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

	private static Baseline baselineOf(Map<String, String> b) {
		return new Baseline(longOrNull(b.get("recent_reels_avg_views")),
				intOrNull(b.get("rank_in_recent_reels")), intOrNull(b.get("recent_reels_count")),
				intOrNull(b.get("recent_contents_count")), decimalOrNull(b.get("recent12_avg_engagement_rate")),
				longOrNull(b.get("recent12_avg_like_count")), longOrNull(b.get("recent12_avg_comment_count")),
				intOrNull(b.get("category_top_percentile")), longOrNull(b.get("category_avg_views")),
				longOrNull(b.get("category_sample_size")));
	}

	private static Long longOrNull(String v) {
		return v == null ? null : new java.math.BigDecimal(v).longValue();
	}

	private static Integer intOrNull(String v) {
		return v == null ? null : new java.math.BigDecimal(v).intValue();
	}

	private static java.math.BigDecimal decimalOrNull(String v) {
		return v == null ? null : new java.math.BigDecimal(v);
	}

	private static Long numberOf(Object v) {
		return v == null ? null : ((Number) v).longValue();
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

	private static String abbreviate(String s) {
		return s.length() > 200 ? s.substring(0, 200) + "…" : s;
	}
}
