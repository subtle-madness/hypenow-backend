package com.celfit.contract.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 인플루언서 상세 계정 요약 1행 (미러: analytics.v_account_summaries → account_summaries).
 * celfit-front AccountReport의 결정(비LLM) 지표 — 산식은 스펙 2026-07-13-c1-account-detail-design.md §3.
 * metric: 'views'|'likes' — 조회수 데이터 부족 계정의 기준 지표 폴백. 트렌드·광고 비교가 이 축을 따른다.
 * avgErPct: 계정 평균 ER(팔로워 분모, %) — 게시물 ER(조회수 분모)과 정의가 다르다.
 * trendDirection 'flat'은 "변화 ±threshold 이내"와 "비교 불가(표본 부족)"를 겸한다 —
 * 후자는 trendOlderAvg가 NULL이고 trendChangePct가 0인 것으로 구분.
 * avgHypeScore: 최근창 콘텐츠 hype_score 단순 평균(0~100), 점수 가능 콘텐츠 없으면 NULL
 * (스펙 2026-07-29-influencer-avg-hype-score).
 * 통계 왜곡 가드 재료(스펙 2026-07-30-perf-summary-statistical-guards-design.md §3-1): *SampleCount는
 * 지표별 실질 모수(analyzedCount와 달리 NULL 관측은 빠진다), medianViews·medianErPct·topViewsSharePct는
 * 관측이 없거나(0건) 분모가 0이면 NULL. windowSpanDays는 최초~최근 게시 간격(일).
 * email: biography 정규식 파싱(스펙 2026-07-30-influencer-email-from-bio) — 첫 매치만·소문자 정규화,
 * biography 없거나 매치 없으면 NULL(LLM 미사용, 운영 실측 오탐 0건 근거).
 */
public record AccountSummary(String handle, Long followers, Long followsCount, Long postsCount,
		String biography, Long analyzedCount, Long viewsCount, String metric, Long avgViews,
		BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgLikes, Long avgComments,
		String trendDirection, Integer trendChangePct, Long trendOlderAvg, Long trendNewerAvg,
		Long sponsoredCount, Long organicAvg, Long adAvg, Integer adDropPct,
		Long comparisonOrganicCount, Long comparisonAdCount, OffsetDateTime lastAdPostedAt,
		OffsetDateTime lastPostedAt, BigDecimal avgIntervalDays, Long avgHypeScore,
		Integer viewsSampleCount, Integer likesSampleCount, Integer commentsSampleCount, Integer reelsCount,
		Integer feedCount, Long medianViews, BigDecimal medianErPct, Integer topViewsSharePct,
		Integer windowSpanDays, String email) {
}
