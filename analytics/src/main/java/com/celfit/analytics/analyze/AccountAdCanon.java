package com.celfit.analytics.analyze;

import com.celfit.analytics.llm.AdSituation;
import com.celfit.analytics.llm.PerfConfidence;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 계정 광고 지표 정본 — <b>content_analyses.ad_type='sponsored'(캡션 분류)</b> 기준.
 *
 * <p>미러의 account_summaries.organic_avg·ad_avg·sponsored_count와 account_content_series.sponsored는
 * raw 뷰(10_account_detail)에서 ad_marked = 인스타 유료파트너십 태그로 집계되는데, 이건 릴스 전용이라
 * 캡션으로만 고지한 광고를 놓친다. 07-17(e203c3c)에 화면(was V1InfluencerReportRepository·Assembler)은
 * 이미 ad_type 정본으로 옮겼지만 카피 잡의 광고 판정만 옛 소스에 남아, 캡션 고지 계정의 adSummary(당시
 * 이름 adHeadline)가 영구 NULL이 됐다(운영 995계정). 산식·비교 성립 조건은 was Assembler.comparison과
 * 동일하게 맞춘다 — adSummary가 설명하는 숫자와 화면 숫자가 갈리면 안 되기 때문.
 *
 * <p>정본 테이블(content_analyses)은 analysis DB에 있어 잡의 analysis JdbcTemplate으로 읽는다.
 * raw DB의 analytics.* 뷰에서는 DB가 달라 조인할 수 없다(§시스템 경계).
 *
 * <p>AccountAnalysisJob(일상 배치)과 ClaudeBurstRunner(구독 버스트 export) 양쪽이 공유한다 —
 * 판정이 복제돼 한쪽만 고쳐지는 재발을 막으려 단일 원천으로 둔다.
 */
final class AccountAdCanon {

	private AccountAdCanon() {
	}

	/** 계정 1건의 광고 집계. 비교 평균은 organic·ad 두 그룹이 모두 있을 때만 채워진다. */
	record AdMetrics(long sponsoredCount, Long organicAvg, Long adAvg, Integer adDropPct,
			long comparisonOrganicCount, long comparisonAdCount, OffsetDateTime lastAdPostedAt) {

		/**
		 * 광고 비교 성립 여부 — organic·협찬 평균이 둘 다 있을 때만. 화면(was)의 comparison 표시
		 * 조건과 같다(한쪽만 있으면 was도 null).
		 */
		boolean hasComparison() {
			return organicAvg != null && adAvg != null;
		}

		/**
		 * adSummary가 무엇을 진술할지 가르는 4분기 (07-21). 비교가 안 돼도 협찬 유무 자체가
		 * 정보라 adSummary를 만든다 — 근거가 아예 없는 INSUFFICIENT만 제외.
		 */
		AdSituation situation() {
			if (hasComparison()) {
				return AdSituation.COMPARABLE;
			}
			if (comparisonOrganicCount > 0 && sponsoredCount == 0) {
				return AdSituation.NO_ADS; // 측정 가능 organic이 있고 협찬은 하나도 없음
			}
			if (comparisonAdCount > 0 && comparisonOrganicCount == 0) {
				return AdSituation.ALL_ADS; // 측정 가능분이 전량 협찬
			}
			// 측정 가능 게시물이 없거나(피드 조회수 NULL 등), 협찬은 있는데 지표가 없어 진술 근거가 없음
			return AdSituation.INSUFFICIENT;
		}
	}

	/**
	 * 기준지표(metric) 값 &gt; 0인 콘텐츠만 ad_type 여부로 그룹지어 집계.
	 * drop%는 원 평균 기준 반올림(표시용 평균은 각자 반올림) — was Assembler와 같은 규약.
	 */
	static AdMetrics load(JdbcTemplate analysis, String handle, String metric) {
		return analysis.queryForObject("""
				WITH m AS (
				  SELECT s.posted_at,
				         CASE WHEN ?::text = 'views' THEN s.views ELSE s.likes END AS mval,
				         COALESCE(an.ad_type = 'sponsored', false) AS sponsored
				  FROM account_content_series s
				  LEFT JOIN content_analyses an ON an.short_code = s.short_code
				  WHERE s.account_handle = ?
				), a AS (
				  SELECT count(*) FILTER (WHERE sponsored)                       AS sponsored_count,
				         avg(mval) FILTER (WHERE NOT sponsored AND mval > 0)     AS organic_raw,
				         avg(mval) FILTER (WHERE sponsored AND mval > 0)         AS ad_raw,
				         count(*) FILTER (WHERE NOT sponsored AND mval > 0)      AS organic_n,
				         count(*) FILTER (WHERE sponsored AND mval > 0)          AS ad_n,
				         max(posted_at) FILTER (WHERE sponsored)                 AS last_ad_posted_at
				  FROM m
				)
				SELECT sponsored_count, organic_n, ad_n, last_ad_posted_at,
				       CASE WHEN organic_n > 0 AND ad_n > 0 THEN round(organic_raw)::bigint END AS organic_avg,
				       CASE WHEN organic_n > 0 AND ad_n > 0 THEN round(ad_raw)::bigint END      AS ad_avg,
				       CASE WHEN organic_n > 0 AND ad_n > 0 AND organic_raw > 0
				            THEN round((1 - ad_raw / organic_raw) * 100)::int END               AS ad_drop_pct
				FROM a""",
				(rs, rowNum) -> new AdMetrics(rs.getLong("sponsored_count"),
						(Long) rs.getObject("organic_avg"), (Long) rs.getObject("ad_avg"),
						(Integer) rs.getObject("ad_drop_pct"),
						rs.getLong("organic_n"), rs.getLong("ad_n"),
						rs.getObject("last_ad_posted_at", OffsetDateTime.class)),
				metric, handle);
	}

	/**
	 * 프롬프트에 실리는 계정 지표를 정본 값으로 치환한다 — 미러 행의 광고 컬럼은 옛 소스라
	 * 그대로 두면 헤드라인 근거 수치(organic_avg·ad_avg·ad_drop_pct)가 화면과 어긋난다.
	 * 원본 맵은 건드리지 않고 컬럼 순서를 유지한 사본을 돌려준다.
	 */
	static Map<String, Object> canonicalSummary(Map<String, Object> summary, AdMetrics ad) {
		Map<String, Object> out = new LinkedHashMap<>(summary);
		out.put("sponsored_count", ad.sponsoredCount());
		out.put("organic_avg", ad.organicAvg());
		out.put("ad_avg", ad.adAvg());
		out.put("ad_drop_pct", ad.adDropPct());
		out.put("comparison_organic_count", ad.comparisonOrganicCount());
		out.put("comparison_ad_count", ad.comparisonAdCount());
		out.put("last_ad_posted_at", ad.lastAdPostedAt());
		return out;
	}

	/**
	 * 판정 전용 내부 컬럼 9개(설계 2026-07-30-perf-summary-statistical-guards §3-1·§3-3) — PerfConfidence
	 * 계산에만 쓰고 LLM 프롬프트에는 노출하지 않는다(수치가 그대로 인용될 여지 차단).
	 *
	 * <p>AccountAnalysisJob(일상 배치)·ClaudeBurstRunner(구독 버스트 export) 양쪽이 이 목록으로
	 * 프롬프트 summary를 벗겨낸다 — 한쪽만 벗겨내면 신뢰도 가드가 조용히 우회되는 구멍이 된다
	 * (07-30 재발 방지: 버스트 경로가 구 5-arg 생성자로 가드를 건너뛰던 문제).
	 *
	 * <p>{@link PerfConfidence#CONFIDENCE_COLUMNS}를 그대로 재사용한다 — 판정에 쓰는 컬럼 목록과
	 * 프롬프트에서 벗겨낼 컬럼 목록이 서로 다른 상수로 따로 관리되면 한쪽만 고쳤을 때 조용히
	 * 어긋나는 드리프트가 생긴다.
	 */
	static final List<String> INTERNAL_CONFIDENCE_COLUMNS = PerfConfidence.CONFIDENCE_COLUMNS;

	/** canonicalSummary 사본 + 그 사본에서 신뢰도 등급 계산에만 쓴 9컬럼을 제거한 프롬프트용 결과. */
	record SummaryWithConfidence(Map<String, Object> promptSummary, PerfConfidence confidence) {}

	/**
	 * 원본 스냅샷(9컬럼 포함)에서 신뢰도 등급을 판정하고, 광고 정본으로 치환한 프롬프트 사본에서는
	 * 그 9컬럼을 제거해 함께 돌려준다 — 판정은 반드시 원본에서 해야 한다(치환·제거 이후에는 판정 근거
	 * 자체가 사라진다).
	 */
	static SummaryWithConfidence withConfidence(Map<String, Object> summary, AdMetrics ad) {
		PerfConfidence confidence = PerfConfidence.of(summary);
		Map<String, Object> promptSummary = canonicalSummary(summary, ad);
		INTERNAL_CONFIDENCE_COLUMNS.forEach(promptSummary::remove);
		return new SummaryWithConfidence(promptSummary, confidence);
	}

	/**
	 * 프롬프트용 게시물 시계열 — 올린 순, 캡션 앞부분 절단, sponsored는 정본(ad_type) 기준.
	 * 요약은 협찬 N건인데 게시물은 전부 false인 자기모순 입력을 막는다(was 차트 bars와 동일 소스).
	 */
	static List<Map<String, Object>> loadPosts(JdbcTemplate analysis, String handle) {
		return analysis.queryForList("""
				SELECT p.posted_at, p.content_type, p.views, p.likes, p.comments,
				       COALESCE(an.ad_type = 'sponsored', false) AS sponsored,
				       left(c.caption, %d) AS caption
				FROM account_content_series p
				LEFT JOIN contents c ON c.short_code = p.short_code
				LEFT JOIN content_analyses an ON an.short_code = p.short_code
				WHERE p.account_handle = ?
				ORDER BY p.posted_at ASC, p.short_code ASC"""
				.formatted(AccountAnalysisJob.CAPTION_CHARS), handle);
	}
}
