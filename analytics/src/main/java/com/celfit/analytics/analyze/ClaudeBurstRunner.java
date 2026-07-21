package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AccountCopy;
import com.celfit.analytics.llm.AccountToAnalyze;
import com.celfit.analytics.llm.BeautyTaxonomy;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.ContentInsightPort.ContentInsight;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.GeminiAccountSynthesizer;
import com.celfit.analytics.llm.GeminiContentAnalyzer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
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

/**
 * 구독 버스트 one-shot (07-19) — 일회성 대량 물량(백필 아님: 제때 크롤된 성숙분 + 카피 신규 유입 버스트)이
 * Gemini 무료 일 한도(1,500콜)를 넘을 때, LLM 호출만 Claude 구독 컴퓨트(claude -p 병렬 드라이버,
 * analytics/export/claude_burst_driver.py)로 빼서 소화한다. 유료 API 키 불필요.
 *
 * <p>export: 미분석 후보(계정 평균 앵커·rank는 최근창 안일 때만)·미카피 계정 → 프롬프트 JSONL({key,system,user}) + 사이드카.
 * collect: 드라이버 결과({key,text}) 파싱·sanitize 후 멱등 INSERT — 프롬프트·sanitize·저장 형태는
 * 일상 파이프라인(GeminiContentAnalyzer·GeminiAccountSynthesizer·ContentAnalysisWriter)과 동일 원천.
 * 실패·미수거 건은 그대로 남아 일상 파이프라인이 자연 흡수한다.
 */
public class ClaudeBurstRunner {

	private static final Logger log = LoggerFactory.getLogger(ClaudeBurstRunner.class);
	private static final List<String> SIDECAR_KEYS = List.of(
			"recent_reels_avg_views", "rank_in_recent_reels", "recent_reels_count",
			"recent_contents_count", "recent12_avg_engagement_rate", "recent12_avg_like_count",
			"recent12_avg_comment_count", "category_top_percentile", "category_avg_views",
			"category_sample_size", "caption");
	/** 클로드는 Gemini response_schema가 없어 스키마 강제를 유저 텍스트로 싣는다. */
	private static final String CONTENT_JSON_RULE = """

			출력 형식: 아래 16개 키를 모두 가진 JSON 객체 하나만 출력하라. 코드펜스·설명·다른 텍스트 금지.
			해당 없는 항목은 null. 키: detectedBrands, sponsoredSignalLevel, sponsoredSignalReasons,
			adDisclosure, detectedProductCategories, detectedProducts, vlmAttributes, mainCategory,
			subCategories, detectedDistributors, adType, aiContentSummary, contentsPattern,
			aiCommentInsight, commentAuthenticityGrade, commentAuthenticityNote""";
	private static final String ACCOUNT_JSON_RULE = """

			출력 형식: 아래 7개 키를 모두 가진 JSON 객체 하나만 출력하라. 코드펜스·설명·다른 텍스트 금지.
			키: tagline, summary, trendNote, chartNote, traits(문자열 배열), adHeadline, paceNote""";

	public record ExportResult(int contents, int accounts) {}

	public record CollectResult(int contents, int accounts, int failed) {}

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final AnalyticsSettings settings;
	private final BeautyTaxonomyLoader taxonomyLoader;
	private final Path workDir;
	private final ObjectMapper om = new ObjectMapper();

	public ClaudeBurstRunner(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			AnalyticsSettings settings, BeautyTaxonomyLoader taxonomyLoader, Path workDir) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.settings = settings;
		this.taxonomyLoader = taxonomyLoader;
		this.workDir = workDir;
	}

	public ExportResult export() {
		write(null, null); // 디렉토리 준비
		int contents = exportContents();
		int accounts = exportAccounts();
		log.info("버스트 export 완료 — 콘텐츠 {}건, 계정 {}건 → {}", contents, accounts, workDir);
		return new ExportResult(contents, accounts);
	}

	/** 체크 예외 격리 — file이 null이면 디렉토리만 만든다. */
	private void write(String file, String content) {
		try {
			Files.createDirectories(workDir);
			if (file != null) {
				Files.writeString(workDir.resolve(file), content);
			}
		} catch (java.io.IOException e) {
			throw new IllegalStateException("버스트 작업 파일 쓰기 실패: " + workDir, e);
		}
	}

	private String readFile(Path path) {
		try {
			return Files.readString(path);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("버스트 작업 파일 읽기 실패: " + path, e);
		}
	}

	/** 미분석 후보 전량 — GeminiBackfillRunner.submit과 동일 대상 정의. 계정 평균은 항상 붙이고
	 * (v_analysis_account_baseline, account_handle 키), rank는 최근창 안일 때만(v_analysis_baseline, short_code
	 * 키 — 밖이면 null). 07-20 스코프 확장: 다작 계정의 최근창 밖 성숙분도 계정 평균을 앵커로 분석. */
	private int exportContents() {
		Set<String> analyzed = new HashSet<>(analysis.queryForList(
				"SELECT short_code FROM content_analyses", String.class));
		List<Map<String, Object>> rows = raw.queryForList("""
				SELECT c.short_code, c.account_handle, c.content_type, c.caption,
				       c.views, c.likes, c.comments,
				       ab.recent_reels_avg_views, b.rank_in_recent_reels, ab.recent_reels_count,
				       ab.recent_contents_count, ab.recent12_avg_engagement_rate,
				       ab.recent12_avg_like_count, ab.recent12_avg_comment_count,
				       ab.category_top_percentile, ab.category_avg_views, ab.category_sample_size
				FROM analytics.v_analysis_candidates c
				LEFT JOIN analytics.v_analysis_account_baseline ab ON ab.account_handle = c.account_handle
				LEFT JOIN analytics.v_analysis_baseline b USING (short_code)
				ORDER BY c.short_code""");
		String system = GeminiContentAnalyzer.instructions(taxonomyLoader.get());
		StringBuilder input = new StringBuilder();
		StringBuilder sidecar = new StringBuilder();
		int count = 0;
		for (Map<String, Object> r : rows) {
			String shortCode = (String) r.get("short_code");
			if (analyzed.contains(shortCode)) {
				continue;
			}
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
			input.append(om.writeValueAsString(om.createObjectNode()
					.put("key", shortCode)
					.put("system", system)
					.put("user", GeminiContentAnalyzer.userText(content) + CONTENT_JSON_RULE)))
					.append('\n');
			var side = om.createObjectNode().put("short_code", shortCode);
			for (String k : SIDECAR_KEYS) {
				Object v = r.get(k);
				if (v == null) {
					side.putNull(k);
				} else {
					side.put(k, v.toString());
				}
			}
			sidecar.append(om.writeValueAsString(side)).append('\n');
			count++;
		}
		write("contents-input.jsonl", input.toString());
		write("contents-sidecar.jsonl", sidecar.toString());
		return count;
	}

	/** 카피 대상 전량 — AccountAnalysisJob.run의 자격 정의에서 배치 상한만 뺐다. */
	private int exportAccounts() {
		List<String> targets = analysis.queryForList("""
				SELECT s.handle
				FROM account_summaries s
				LEFT JOIN LATERAL (
				  SELECT a.input_last_posted_at, a.analyzed_at
				  FROM account_analyses a WHERE a.handle = s.handle
				  ORDER BY a.analyzed_at DESC LIMIT 1
				) latest ON true
				WHERE latest.analyzed_at IS NULL
				   OR (latest.input_last_posted_at IS DISTINCT FROM s.last_posted_at
				       AND latest.analyzed_at < now() - make_interval(days => ?))
				ORDER BY s.handle""", String.class, settings.accountAnalyzeCooldownDays());
		StringBuilder input = new StringBuilder();
		StringBuilder sidecar = new StringBuilder();
		for (String handle : targets) {
			Map<String, Object> summary = analysis.queryForMap(
					"SELECT * FROM account_summaries WHERE handle = ?", handle);
			OffsetDateTime lastPostedAt = analysis.queryForObject(
					"SELECT last_posted_at FROM account_summaries WHERE handle = ?",
					OffsetDateTime.class, handle);
			List<Map<String, Object>> categories = analysis.queryForList("""
					SELECT main_group, content_count FROM account_category_stats
					WHERE account_handle = ? ORDER BY content_count DESC, main_group ASC""", handle);
			List<Map<String, Object>> posts = analysis.queryForList("""
					SELECT p.posted_at, p.content_type, p.views, p.likes, p.comments, p.sponsored,
					       left(c.caption, %d) AS caption
					FROM account_content_series p
					LEFT JOIN contents c ON c.short_code = p.short_code
					WHERE p.account_handle = ?
					ORDER BY p.posted_at ASC, p.short_code ASC"""
					.formatted(AccountAnalysisJob.CAPTION_CHARS), handle);
			boolean hasAdComparison = summary.get("organic_avg") != null && summary.get("ad_avg") != null;
			AccountToAnalyze account = new AccountToAnalyze(handle, summary, categories, posts, hasAdComparison);
			input.append(om.writeValueAsString(om.createObjectNode()
					.put("key", handle)
					.put("system", GeminiAccountSynthesizer.instructions())
					.put("user", GeminiAccountSynthesizer.userText(account) + ACCOUNT_JSON_RULE)))
					.append('\n');
			var side = om.createObjectNode().put("handle", handle)
					.put("has_ad_comparison", hasAdComparison);
			Long analyzedCount = (Long) summary.get("analyzed_count");
			if (analyzedCount == null) {
				side.putNull("analyzed_count");
			} else {
				side.put("analyzed_count", analyzedCount);
			}
			if (lastPostedAt == null) {
				side.putNull("input_last_posted_at");
			} else {
				side.put("input_last_posted_at", lastPostedAt.toString());
			}
			sidecar.append(om.writeValueAsString(side)).append('\n');
		}
		write("accounts-input.jsonl", input.toString());
		write("accounts-sidecar.jsonl", sidecar.toString());
		return targets.size();
	}

	public CollectResult collect() {
		String model = settings.llmModel();
		BeautyTaxonomy taxonomy = taxonomyLoader.get();
		List<String> contentKeys = new java.util.ArrayList<>(List.of("short_code"));
		contentKeys.addAll(SIDECAR_KEYS);
		Map<String, Map<String, String>> contentSidecar =
				readSidecar("contents-sidecar.jsonl", "short_code", contentKeys);
		int contents = 0;
		int accounts = 0;
		int failed = 0;
		for (JsonNode line : readResults("contents-results.jsonl")) {
			String shortCode = line.path("key").asString("");
			try {
				ContentInsight insight = GeminiContentAnalyzer.parse(om,
						stripFences(line.path("text").asString("")), taxonomy);
				if (insight.synthesis().aiContentSummary() == null
						|| insight.synthesis().aiContentSummary().isBlank()) {
					failed++;
					continue;
				}
				Map<String, String> base = contentSidecar.get(shortCode);
				if (base == null) {
					failed++;
					log.warn("사이드카에 없는 key: {}", shortCode);
					continue;
				}
				boolean hasCaption = base.get("caption") != null && !base.get("caption").isBlank();
				// 버스트는 백필 아님 — 제때 크롤된 성숙 후보(v_analysis_candidates)라 일상 잡과 동일 timely 마킹 (V33).
				ContentAnalysisWriter.insert(analysis, om, shortCode, model, baselineOf(base),
						hasCaption ? insight.attributes() : null, insight.synthesis(), true, "timely");
				contents++;
			} catch (Exception e) {
				failed++;
				log.warn("콘텐츠 결과 저장 실패: {}", shortCode, e);
			}
		}
		Map<String, Map<String, String>> accountSidecar = readSidecar("accounts-sidecar.jsonl", "handle",
				List.of("handle", "has_ad_comparison", "analyzed_count", "input_last_posted_at"));
		for (JsonNode line : readResults("accounts-results.jsonl")) {
			String handle = line.path("key").asString("");
			try {
				if (insertAccountCopy(handle, stripFences(line.path("text").asString("")),
						accountSidecar.get(handle), model)) {
					accounts++;
				} else {
					failed++;
				}
			} catch (Exception e) {
				failed++;
				log.warn("계정 카피 저장 실패: {}", handle, e);
			}
		}
		log.info("버스트 collect 완료 — 콘텐츠 {}건, 계정 {}건 저장, {}건 실패(잔여는 일상 파이프라인 흡수)",
				contents, accounts, failed);
		return new CollectResult(contents, accounts, failed);
	}

	private boolean insertAccountCopy(String handle, String json, Map<String, String> side, String model) {
		if (side == null) {
			log.warn("사이드카에 없는 handle: {}", handle);
			return false;
		}
		AccountCopy copy = om.readValue(json, AccountCopy.class);
		// AccountAnalysisJob.analyzeOne과 동일 가드 — 빈 카피가 최신 행으로 서빙되는 것 차단
		if (isBlank(copy.tagline()) || isBlank(copy.summary())
				|| copy.traits() == null || copy.traits().isEmpty()) {
			return false;
		}
		OffsetDateTime inputLastPostedAt = side.get("input_last_posted_at") == null
				? null : OffsetDateTime.parse(side.get("input_last_posted_at"));
		// 멱등 가드: 같은 입력 스냅샷의 카피가 이미 있으면 재수집 스킵 (이력 테이블이라 ON CONFLICT 불가)
		Integer dup = analysis.queryForObject("""
				SELECT count(*) FROM account_analyses
				WHERE handle = ? AND model = ? AND input_last_posted_at IS NOT DISTINCT FROM ?""",
				Integer.class, handle, model, inputLastPostedAt);
		if (dup != null && dup > 0) {
			return false;
		}
		List<String> traits = List.copyOf(copy.traits().size() > AccountAnalysisJob.MAX_TRAITS
				? copy.traits().subList(0, AccountAnalysisJob.MAX_TRAITS) : copy.traits());
		boolean hasAdComparison = Boolean.parseBoolean(side.get("has_ad_comparison"));
		analysis.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  input_analyzed_count, tagline, summary, trend_note, chart_note, traits,
				  ad_headline, pace_note)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
				handle, OffsetDateTime.now(), model, inputLastPostedAt,
				side.get("analyzed_count") == null ? null : Long.parseLong(side.get("analyzed_count")),
				copy.tagline(), copy.summary(), copy.trendNote(), copy.chartNote(),
				om.writeValueAsString(traits),
				hasAdComparison && !isBlank(copy.adHeadline()) ? copy.adHeadline() : null,
				copy.paceNote());
		return true;
	}

	private List<JsonNode> readResults(String file) {
		Path path = workDir.resolve(file);
		if (!Files.exists(path)) {
			log.warn("결과 파일 없음 — 건너뜀: {}", path);
			return List.of();
		}
		java.util.ArrayList<JsonNode> lines = new java.util.ArrayList<>();
		for (String line : readFile(path).split("\n")) {
			if (!line.isBlank()) {
				lines.add(om.readTree(line));
			}
		}
		return lines;
	}

	private Map<String, Map<String, String>> readSidecar(String file, String keyField, List<String> keys) {
		Path path = workDir.resolve(file);
		if (!Files.exists(path)) {
			return Map.of();
		}
		Map<String, Map<String, String>> out = new LinkedHashMap<>();
		for (String line : readFile(path).split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node = om.readTree(line);
			Map<String, String> vals = new LinkedHashMap<>();
			for (String k : keys) {
				JsonNode v = node.path(k);
				vals.put(k, v.isNull() || v.isMissingNode() ? null : v.asString());
			}
			out.put(node.path(keyField).asString(), vals);
		}
		return out;
	}

	/** 클로드가 지시를 어기고 코드펜스로 감쌌을 때 대비 — 첫 줄 ```(json)과 끝 ```을 벗긴다. */
	static String stripFences(String text) {
		String t = text.strip();
		if (t.startsWith("```")) {
			int firstNewline = t.indexOf('\n');
			t = firstNewline < 0 ? "" : t.substring(firstNewline + 1);
			if (t.endsWith("```")) {
				t = t.substring(0, t.length() - 3);
			}
		}
		return t.strip();
	}

	private static Baseline baselineOf(Map<String, String> b) {
		return new Baseline(longOrNull(b.get("recent_reels_avg_views")),
				intOrNull(b.get("rank_in_recent_reels")), intOrNull(b.get("recent_reels_count")),
				intOrNull(b.get("recent_contents_count")), decimalOrNull(b.get("recent12_avg_engagement_rate")),
				longOrNull(b.get("recent12_avg_like_count")), longOrNull(b.get("recent12_avg_comment_count")),
				intOrNull(b.get("category_top_percentile")), longOrNull(b.get("category_avg_views")),
				longOrNull(b.get("category_sample_size")));
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
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
}
