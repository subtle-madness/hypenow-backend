package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.Synthesis;
import com.celfit.analytics.llm.SynthesisPort;
import com.celfit.analytics.llm.VisionPort;
import com.celfit.analytics.llm.VlmResult;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 콘텐츠 분석 배치 (스펙 §6). 분석 시점 고정·불변 — INSERT만, 재분석 없음.
 * 대상: 미분석 AND (댓글 없음 OR 분류 완료) — classify 선행을 강제.
 * 콘텐츠 단위 실패 격리: 한 건 실패는 로그 후 계속 (B2 리뷰 반영).
 */
public class ContentAnalysisJob {

	private static final Logger log = LoggerFactory.getLogger(ContentAnalysisJob.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final SynthesisPort synthesis;
	private final VisionPort vision; // vlmEnabled=false면 null 허용
	private final AnalyticsSettings settings;
	private final boolean vlmEnabled;
	private final ObjectMapper json = new ObjectMapper();

	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			SynthesisPort synthesis, VisionPort vision, AnalyticsSettings settings, boolean vlmEnabled) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.synthesis = synthesis;
		this.vision = vision;
		this.settings = settings;
		this.vlmEnabled = vlmEnabled;
	}

	/**
	 * @return 분석 완료 콘텐츠 수
	 *
	 * <p>대상은 양쪽 DB 교집합: 기준선은 "최근 N개" 비교 지표라 윈도우 밖 콘텐츠는
	 * 분석 대상이 아니다 (기준선 정의 불가). 미러 전체(contents)에서 기준선 뷰에 없는
	 * 콘텐츠를 상한 적용 전에 걸러내지 않으면 매 실행 예외→skip으로 배치 상한 슬롯을
	 * 영구 잠식한다 (B2의 classified HashSet 패턴과 동일한 자바 측 필터).
	 */
	public int run() {
		Set<String> withBaseline = new HashSet<>(raw.queryForList(
				"SELECT short_code FROM analytics.v_analysis_baseline", String.class));
		List<String> targets = analysis.queryForList("""
				SELECT c.short_code FROM contents c
				WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
				  AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
				       OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
				ORDER BY c.short_code""", String.class).stream()
				.filter(withBaseline::contains)
				.limit(settings.analyzeBatchLimit())
				.toList();
		String model = settings.llmModel();
		int processed = 0;
		int failed = 0;
		for (String shortCode : targets) {
			try {
				analyzeOne(shortCode, model);
				processed++;
			} catch (Exception e) {
				failed++;
				log.error("analysis failed for {} — 다음 실행에서 재대상", shortCode, e);
			}
		}
		log.info("analysis complete ({} contents, {} failed)", processed, failed);
		return processed;
	}

	private void analyzeOne(String shortCode, String model) {
		Map<String, Object> content = analysis.queryForMap("""
				SELECT account_handle, caption, content_type, thumbnail_url, views, likes, comments
				FROM contents WHERE short_code = ?""", shortCode);
		Map<String, Long> categoryCounts = new LinkedHashMap<>();
		analysis.query("""
				SELECT ai_category, count(*) AS cnt FROM comment_classifications
				WHERE short_code = ? GROUP BY ai_category""",
				rs -> {
					categoryCounts.put(rs.getString(1), rs.getLong(2));
				}, shortCode);
		Baseline b = raw.queryForObject("""
				SELECT recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate,
				       recent12_avg_like_count, recent12_avg_comment_count,
				       category_top_percentile, category_avg_views, category_sample_size
				FROM analytics.v_analysis_baseline WHERE short_code = ?""",
				(rs, i) -> new Baseline(
						// PG 타입이 numeric(round)·bigint(rank/count)·smallint(::smallint)로 섞여 있어
						// getObject 캐스트는 CCE/PSQLException 지뢰 — 전부 BigDecimal로 읽어 변환한다
						longOf(rs.getBigDecimal(1)), intOf(rs.getBigDecimal(2)), intOf(rs.getBigDecimal(3)),
						intOf(rs.getBigDecimal(4)), rs.getBigDecimal(5),
						longOf(rs.getBigDecimal(6)), longOf(rs.getBigDecimal(7)),
						intOf(rs.getBigDecimal(8)), longOf(rs.getBigDecimal(9)), longOf(rs.getBigDecimal(10))),
				shortCode);
		VlmResult vlm = (vlmEnabled && vision != null)
				? vision.analyze((String) content.get("thumbnail_url"), (String) content.get("caption"))
				: null;
		Map<String, Object> baselineForPrompt = new LinkedHashMap<>();
		baselineForPrompt.put("recent_reels_avg_views", b.recentReelsAvgViews());
		baselineForPrompt.put("rank_in_recent_reels", b.rankInRecentReels());
		baselineForPrompt.put("recent_contents_count", b.recentContentsCount());
		baselineForPrompt.put("recent12_avg_engagement_rate", b.recent12AvgEngagementRate());
		baselineForPrompt.put("recent12_avg_like_count", b.recent12AvgLikeCount());
		baselineForPrompt.put("recent12_avg_comment_count", b.recent12AvgCommentCount());
		baselineForPrompt.put("category_top_percentile", b.categoryTopPercentile());
		Synthesis s = synthesis.synthesize(new ContentToAnalyze(shortCode,
				(String) content.get("account_handle"), (String) content.get("caption"),
				(String) content.get("content_type"), (Long) content.get("views"),
				(Long) content.get("likes"), (Long) content.get("comments"),
				baselineForPrompt, categoryCounts));
		// content_analyses는 불변(INSERT만)이라 빈 결과가 저장되면 영구 고정 + 재분석 대상에서도 제외된다.
		// 저장 전에 실패 처리해 콘텐츠 단위 try/catch가 skip → 다음 실행에서 재대상되게 한다.
		if (s.aiContentSummary() == null || s.aiContentSummary().isBlank()) {
			throw new IllegalStateException("종합 텍스트가 비어 있음: " + shortCode);
		}
		analysis.update("""
				INSERT INTO content_analyses (short_code, model,
				  ai_content_summary, contents_pattern, ai_comment_insight,
				  recent_reels_avg_views, rank_in_recent_reels, recent_reels_count, recent_contents_count,
				  recent12_avg_engagement_rate, recent12_avg_like_count, recent12_avg_comment_count,
				  category_top_percentile, category_avg_views, category_sample_size,
				  detected_brands, sponsored_signal_level, sponsored_signal_reasons, ad_disclosure,
				  detected_product_categories, vlm_attributes, main_category, sub_categories, ad_type,
				  comment_authenticity_grade, comment_authenticity_note)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
				        ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?, ?)""",
				shortCode, model,
				s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
				b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentReelsCount(), b.recentContentsCount(),
				b.recent12AvgEngagementRate(), b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
				b.categoryTopPercentile(), b.categoryAvgViews(), b.categorySampleSize(),
				toJson(vlm == null ? null : vlm.detectedBrands()),
				vlm == null ? null : vlm.sponsoredSignalLevel(),
				toJson(vlm == null ? null : vlm.sponsoredSignalReasons()),
				vlm == null ? null : vlm.adDisclosure(),
				toJson(vlm == null ? null : vlm.detectedProductCategories()),
				toJson(vlm == null ? null : vlm.vlmAttributes()),
				vlm == null ? null : vlm.mainCategory(),
				toJson(vlm == null ? null : vlm.subCategories()),
				vlm == null ? null : vlm.adType(),
				s.commentAuthenticityGrade(), s.commentAuthenticityNote());
	}

	private String toJson(Object value) {
		return value == null ? null : json.writeValueAsString(value);
	}

	private static Long longOf(java.math.BigDecimal v) {
		return v == null ? null : v.longValueExact();
	}

	private static Integer intOf(java.math.BigDecimal v) {
		return v == null ? null : v.intValueExact();
	}
}
