package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.ContentInsightPort;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.Synthesis;
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
 * 대상: 미분석 AND (댓글 없음 OR 분류 완료) AND 게시 후 N일 경과(기본 3 — B3 숙성 가드).
 * 속성 분석은 캡션 주·썸네일 보조 (2026-07-14 캡션 분류 스펙) — 썸네일 만료여도 캡션으로 5종 산출.
 * 콘텐츠 단위 실패 격리: 한 건 실패는 로그 후 계속 (B2 리뷰 반영).
 */
public class ContentAnalysisJob {

	private static final Logger log = LoggerFactory.getLogger(ContentAnalysisJob.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final ContentInsightPort insight; // ②속성+③종합 통합 1콜 (07-18 확정)
	private final AnalyticsSettings settings;
	private final boolean thumbnailEnabled; // 썸네일 첨부 게이트 — off여도 캡션 기반 속성은 산출
	private final Predicate<String> thumbnailAlive;
	private final ObjectMapper json = new ObjectMapper();

	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.insight = insight;
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
		// 최신 수집순: 썸네일 서명 URL(만료 ~4일)이 살아있을 때 VLM을 시도하기 위한 정렬 (B3 VLM 잔여분).
		// 기준선을 여기서 통째로 로드한다 — 뷰 평가가 운영 실측 분 단위(07-19, 27k 기준 4.5분)라
		// 건당 WHERE 조회를 반복하면 배치가 뷰 스캔에 잠긴다. 1회 평가 후 메모리 맵 조회로 대체.
		Map<String, Baseline> withBaseline = new LinkedHashMap<>();
		raw.query("""
				SELECT short_code, recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate,
				       recent12_avg_like_count, recent12_avg_comment_count,
				       category_top_percentile, category_avg_views, category_sample_size
				FROM analytics.v_analysis_baseline ORDER BY captured_at DESC""",
				rs -> {
					// PG 타입이 numeric·bigint·smallint로 섞여 있어 전부 BigDecimal로 읽어 변환 (기존 관용구)
					withBaseline.put(rs.getString(1), new Baseline(
							longOf(rs.getBigDecimal(2)), intOf(rs.getBigDecimal(3)), intOf(rs.getBigDecimal(4)),
							intOf(rs.getBigDecimal(5)), rs.getBigDecimal(6),
							longOf(rs.getBigDecimal(7)), longOf(rs.getBigDecimal(8)),
							intOf(rs.getBigDecimal(9)), longOf(rs.getBigDecimal(10)), longOf(rs.getBigDecimal(11))));
				});
		// 숙성 가드: 게시 후 N일(기본 3) 경과분만 — 불변 테이블이라 게시 직후 분석되면 영구 고정 (07-14 확정).
		// posted_at NULL은 부등식에서 자연 제외 (게시일 미상이면 숙성 판정 불가).
		Set<String> eligible = new HashSet<>(analysis.queryForList("""
				SELECT c.short_code FROM contents c
				WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
				  AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
				       OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
				  AND c.posted_at <= now() - make_interval(days => ?)""",
				String.class, settings.analyzeMaturityDays()));
		List<String> targets = withBaseline.keySet().stream()
				.filter(eligible::contains)
				.limit(settings.analyzeBatchLimit())
				.toList();
		String model = settings.activeLlmModel();
		int processed = 0;
		int failed = 0;
		for (String shortCode : targets) {
			try {
				analyzeOne(shortCode, model, withBaseline.get(shortCode));
				processed++;
			} catch (com.celfit.analytics.llm.LlmQuotaExhaustedException e) {
				// 일 한도 소진 — 에러가 아닌 이월: 남은 대상은 다음 실행에서 자연 재대상 (07-18 확정)
				log.warn("LLM 일 한도 소진 — 배치 중단, 잔여 {}건 이월", targets.size() - processed - failed);
				break;
			} catch (Exception e) {
				failed++;
				log.error("analysis failed for {} — 다음 실행에서 재대상", shortCode, e);
			}
		}
		log.info("analysis complete ({} contents, {} failed)", processed, failed);
		return processed;
	}

	private void analyzeOne(String shortCode, String model, Baseline b) {
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
		// 캡션 주·썸네일 보조: 썸네일은 게이트 on + 프리체크 생존일 때만 첨부, 만료·off여도 캡션으로 5종 산출.
		// 통합 1콜(속성+종합 — 07-18 확정) 예외(일시 장애)는 기존대로 콘텐츠 실패 → 다음 실행 재대상.
		String caption = (String) content.get("caption");
		String thumbnailUrl = (String) content.get("thumbnail_url");
		boolean attachThumbnail = thumbnailEnabled && thumbnailUrl != null && thumbnailAlive.test(thumbnailUrl);
		if (thumbnailEnabled && thumbnailUrl != null && !attachThumbnail) {
			log.info("썸네일 만료/접근 불가 — 캡션만으로 속성 분석: {}", shortCode);
		}
		boolean hasCaption = caption != null && !caption.isBlank();
		Map<String, Object> baselineForPrompt = new LinkedHashMap<>();
		baselineForPrompt.put("recent_reels_avg_views", b.recentReelsAvgViews());
		baselineForPrompt.put("rank_in_recent_reels", b.rankInRecentReels());
		baselineForPrompt.put("recent_contents_count", b.recentContentsCount());
		baselineForPrompt.put("recent12_avg_engagement_rate", b.recent12AvgEngagementRate());
		baselineForPrompt.put("recent12_avg_like_count", b.recent12AvgLikeCount());
		baselineForPrompt.put("recent12_avg_comment_count", b.recent12AvgCommentCount());
		baselineForPrompt.put("category_top_percentile", b.categoryTopPercentile());
		ContentInsightPort.ContentInsight result = insight.analyze(new ContentToAnalyze(shortCode,
				(String) content.get("account_handle"), caption,
				(String) content.get("content_type"), (Long) content.get("views"),
				(Long) content.get("likes"), (Long) content.get("comments"),
				baselineForPrompt, categoryCounts), attachThumbnail ? thumbnailUrl : null);
		// 캡션도 썸네일도 없으면 속성 근거 입력이 없다 — 통합 콜이 돌려줘도 폐기하고 속성 컬럼 NULL 유지.
		ContentAttributes attrs = hasCaption || attachThumbnail ? result.attributes() : null;
		Synthesis s = result.synthesis();
		// content_analyses는 불변(INSERT만)이라 빈 결과가 저장되면 영구 고정 + 재분석 대상에서도 제외된다.
		// 저장 전에 실패 처리해 콘텐츠 단위 try/catch가 skip → 다음 실행에서 재대상되게 한다.
		if (s.aiContentSummary() == null || s.aiContentSummary().isBlank()) {
			throw new IllegalStateException("종합 텍스트가 비어 있음: " + shortCode);
		}
		ContentAnalysisWriter.insert(analysis, json, shortCode, model, b, attrs, s, false);
	}

	private static Long longOf(java.math.BigDecimal v) {
		return v == null ? null : v.longValueExact();
	}

	private static Integer intOf(java.math.BigDecimal v) {
		return v == null ? null : v.intValueExact();
	}
}
