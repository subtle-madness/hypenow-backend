package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentAttributePort;
import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.Synthesis;
import com.celfit.analytics.llm.SynthesisPort;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 콘텐츠 분석 배치 (스펙 §6). 분석 시점 고정·불변 — INSERT만, 재분석 없음.
 * 대상: 미분석 AND (댓글 없음 OR 분류 완료) — classify 선행을 강제.
 * 속성 분석은 캡션 주·썸네일 보조 (2026-07-14 캡션 분류 스펙) — 썸네일 만료여도 캡션으로 5종 산출.
 * 콘텐츠 단위 실패 격리: 한 건 실패는 로그 후 계속 (B2 리뷰 반영).
 */
public class ContentAnalysisJob {

	private static final Logger log = LoggerFactory.getLogger(ContentAnalysisJob.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final SynthesisPort synthesis;
	private final ContentAttributePort attributes;
	private final AnalyticsSettings settings;
	private final boolean thumbnailEnabled; // 썸네일 첨부 게이트 — off여도 캡션 기반 속성은 산출
	private final Predicate<String> thumbnailAlive;
	private final ObjectMapper json = new ObjectMapper();

	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			SynthesisPort synthesis, ContentAttributePort attributes, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.synthesis = synthesis;
		this.attributes = attributes;
		this.settings = settings;
		this.thumbnailEnabled = thumbnailEnabled;
		this.thumbnailAlive = thumbnailAlive;
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
		// 최신 수집순: 썸네일 서명 URL(만료 ~4일)이 살아있을 때 VLM을 시도하기 위한 정렬 (B3 VLM 잔여분)
		List<String> withBaseline = raw.queryForList(
				"SELECT short_code FROM analytics.v_analysis_baseline ORDER BY captured_at DESC", String.class);
		Set<String> eligible = new HashSet<>(analysis.queryForList("""
				SELECT c.short_code FROM contents c
				WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
				  AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
				       OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))""",
				String.class));
		List<String> targets = withBaseline.stream()
				.filter(eligible::contains)
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
		// 캡션 주·썸네일 보조: 썸네일은 게이트 on + 프리체크 생존일 때만 첨부, 만료·off여도 캡션으로 5종 산출.
		// 캡션도 썸네일도 없으면 속성 분석에 넣을 입력이 없다 — 속성 컬럼만 NULL로 저장 (행 자체는 생성).
		// 속성 API 호출 예외(일시 장애)는 기존대로 콘텐츠 실패 → 다음 실행 재대상.
		String caption = (String) content.get("caption");
		String thumbnailUrl = (String) content.get("thumbnail_url");
		boolean attachThumbnail = thumbnailEnabled && thumbnailUrl != null && thumbnailAlive.test(thumbnailUrl);
		if (thumbnailEnabled && thumbnailUrl != null && !attachThumbnail) {
			log.info("썸네일 만료/접근 불가 — 캡션만으로 속성 분석: {}", shortCode);
		}
		boolean hasCaption = caption != null && !caption.isBlank();
		ContentAttributes attrs = hasCaption || attachThumbnail
				? attributes.analyze(caption, attachThumbnail ? thumbnailUrl : null)
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
				(String) content.get("account_handle"), caption,
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
				  detected_product_categories, detected_products, vlm_attributes, main_category, sub_categories,
				  detected_distributors, ad_type,
				  comment_authenticity_grade, comment_authenticity_note)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
				        ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""",
				shortCode, model,
				s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
				b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentReelsCount(), b.recentContentsCount(),
				b.recent12AvgEngagementRate(), b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
				b.categoryTopPercentile(), b.categoryAvgViews(), b.categorySampleSize(),
				toJson(attrs == null ? null : attrs.detectedBrands()),
				attrs == null ? null : attrs.sponsoredSignalLevel(),
				toJson(attrs == null ? null : attrs.sponsoredSignalReasons()),
				attrs == null ? null : attrs.adDisclosure(),
				toJson(attrs == null ? null : attrs.detectedProductCategories()),
				toJson(attrs == null ? null : attrs.detectedProducts()),
				toJson(attrs == null ? null : attrs.vlmAttributes()),
				attrs == null ? null : attrs.mainCategory(),
				toJson(attrs == null ? null : attrs.subCategories()),
				toJson(attrs == null ? null : attrs.detectedDistributors()),
				attrs == null ? null : attrs.adType(),
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
