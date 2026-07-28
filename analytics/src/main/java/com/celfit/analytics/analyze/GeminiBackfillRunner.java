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
 * 초기 백필 one-shot — 배치 API 일회 실행. 07-20 개정: provider=vertex면 Vertex 배치(GCS,
 * 잡 이름은 projects/{p}/locations/{loc}/batchPredictionJobs/{id} 전체 경로), 아니면
 * AI Studio 유료 키(GEMINI_API_KEY_PAID) Batch(배치 이름 batches/NNN) — 배선은 JobConfig.
 * submit: v_analysis_candidates 미분석 전량(계정 평균 앵커·rank는 최근창 안일 때만 — 07-20 스코프 확장,
 *         #79 재통합) → JSONL 업로드 → 배치 생성 →
 *         사이드카(프롬프트에 실은 기준선 스냅샷) 저장. 캡션 단독(썸네일 미첨부 — 서명 URL 대부분 만료).
 * collect: 상태 확인 → 결과 다운로드 → 파싱·sanitize → ON CONFLICT DO NOTHING INSERT(재실행 멱등).
 * 실행: --spring.main.web-application-type=none --analytics.backfill-submit=true
 *   → 로그의 배치 잡 이름 그대로 --analytics.backfill-collect=<잡 이름>
 */
public class GeminiBackfillRunner {

	private static final Logger log = LoggerFactory.getLogger(GeminiBackfillRunner.class);
	private static final List<String> SIDECAR_KEYS = List.of(
			"recent_reels_avg_views", "rank_in_recent_reels", "recent_reels_count",
			"recent_contents_count", "recent12_avg_engagement_rate", "recent12_avg_like_count",
			"recent12_avg_comment_count", "category_top_percentile", "category_avg_views",
			"category_sample_size", "caption", "timely");

	private static final java.util.regex.Pattern ECHO_SHORT_CODE =
			java.util.regex.Pattern.compile("^콘텐츠: (\\S+) \\(");

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
		// 미분석 후보 전량 — 일상 잡·버스트와 동일 정의 (07-20 스코프 확장). 계정 평균(recent12_avg_*·
		// recent_reels_avg_views 등)은 account_handle 키로 항상 붙이고, rank는 최근창 안일 때만(short_code
		// 키 — 밖이면 null). 다작 계정의 최근창 밖 성숙분도 계정 평균을 앵커로 분석 대상에 포함.
		List<Map<String, Object>> rows = raw.queryForList("""
				SELECT c.short_code, c.account_handle, c.content_type, c.caption,
				       c.views, c.likes, c.comments, c.timely, c.ad_marked,
				       ab.recent_reels_avg_views, b.rank_in_recent_reels, ab.recent_reels_count,
				       ab.recent_contents_count, ab.recent12_avg_engagement_rate,
				       ab.recent12_avg_like_count, ab.recent12_avg_comment_count,
				       ab.category_top_percentile, ab.category_avg_views, ab.category_sample_size
				FROM v_analysis_candidates c
				LEFT JOIN v_analysis_account_baseline ab ON ab.account_handle = c.account_handle
				LEFT JOIN v_analysis_baseline b USING (short_code)
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

	/** JSONL 요청 라인 — key=short_code, request=GenerateContentRequest(camelCase — proto JSON은
	 *  양쪽 표기를 다 받지만 AI Studio·Vertex 공용으로 통일). */
	ObjectNode requestLine(String shortCode, Map<String, Object> r, String system) {
		Map<String, Object> baseline = PromptBaseline.ofRow(r);
		ContentToAnalyze content = new ContentToAnalyze(shortCode, (String) r.get("account_handle"),
				(String) r.get("caption"), (String) r.get("content_type"),
				numberOf(r.get("views")), numberOf(r.get("likes")), numberOf(r.get("comments")),
				baseline, Map.of(), (Boolean) r.get("ad_marked"));
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
				text(batch, "metadata", "dest", "fileName"),
				text(batch, "outputInfo", "gcsOutputDirectory"));
		if (resultFile == null) {
			throw new IllegalStateException("결과 파일 이름을 찾지 못함 — 배치 응답: " + batch);
		}
		Map<String, Map<String, String>> sidecar = readSidecar();
		String model = settings.geminiModel();
		BeautyTaxonomy taxonomy = taxonomyLoader.get();
		// 결과(운영 실측 119MB+)는 스트리밍으로 한 줄씩 받아 즉시 파싱·INSERT — 전체 적재 금지(07-20 OOM)
		java.util.concurrent.atomic.AtomicInteger saved = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
		api.downloadResults(resultFile, line -> {
			try {
				JsonNode node = om.readTree(line);
				String vertexStatus = node.path("status").asString("");
				if (!vertexStatus.isEmpty()) {
					failed.incrementAndGet();
					log.warn("배치 실패 라인 (status={}): {}", vertexStatus, abbreviate(line));
					return;
				}
				String shortCode = node.path("key").asString("");
				if (shortCode.isEmpty()) {
					shortCode = shortCodeFromEcho(node);
				}
				JsonNode text = node.path("response").path("candidates").path(0)
						.path("content").path("parts").path(0).path("text");
				if (shortCode.isEmpty() || text.isMissingNode()) {
					failed.incrementAndGet();
					log.warn("결과 라인 해석 불가/오류 응답: {}", abbreviate(line));
					return;
				}
				ContentInsight insight = GeminiContentAnalyzer.parse(om, text.asString(), taxonomy);
				if (insight.synthesis().aiContentSummary() == null
						|| insight.synthesis().aiContentSummary().isBlank()) {
					failed.incrementAndGet();
					return;
				}
				Map<String, String> base = sidecar.get(shortCode);
				if (base == null) {
					failed.incrementAndGet();
					log.warn("사이드카에 없는 key: {}", shortCode);
					return;
				}
				boolean hasCaption = base.get("caption") != null && !base.get("caption").isBlank();
				// 07-20 개정: 04 뷰가 timely 후보도 포함(제때 크롤 OR 최근 N 윈도우) — 마킹은 뷰의 timely
				// 판정을 그대로 승계한다. 구버전 사이드카(timely 키 없음)는 NULL→false로 late_backfill 폴백.
				boolean timely = "true".equals(base.get("timely"));
				ContentAnalysisWriter.insert(analysis, om, shortCode, model, baselineOf(base),
						hasCaption ? insight.attributes() : null, insight.synthesis(), true,
						timely ? "timely" : "late_backfill");
				saved.incrementAndGet();
			} catch (Exception e) {
				failed.incrementAndGet();
				log.warn("결과 라인 저장 실패: {}", abbreviate(line), e);
			}
		});
		log.info("백필 저장 완료 — {}건 저장, {}건 실패(잔여는 일상 파이프라인이 흡수)", saved.get(), failed.get());
		return saved.get();
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
