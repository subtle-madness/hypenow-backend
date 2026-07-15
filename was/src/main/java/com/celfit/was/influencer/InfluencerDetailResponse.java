package com.celfit.was.influencer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 인플루언서 상세 응답 — celfit-front AccountReport의 결정(비LLM) 데이터.
 * profile = accounts ⊕ account_summaries, report = account_summaries + category_stats + content_series 조립.
 * LLM 카피 7종(tagline·summary·trend.note·traits·ads.headline·brands·paceNote)은 이 record에 없다 —
 * C2 additive 확장 지점(스펙 2026-07-13-c1-account-detail-design.md §4).
 */
public record InfluencerDetailResponse(Profile profile, Report report) {

	/** displayName·profileImageUrl은 accounts 전용 — accounts 부재 시 null(D의 account 규약과 동일). */
	public record Profile(
			String handle,
			String displayName,
			String profileImageUrl,
			Long followers,
			Long followsCount,
			Long postsCount,
			String biography) {
	}

	/** totalPosts = posts_count("전체 N개 중 최근 M개" 카피의 분모). */
	public record Report(
			Long totalPosts,
			Long analyzedCount,
			Stats stats,
			Trend trend,
			Chart chart,
			ContentMix contentMix,
			Ads ads,
			Activity activity) {

		public record Stats(
				String metric,
				Long avgViews,
				BigDecimal viewsPerFollower,
				BigDecimal avgErPct,
				Long avgLikes,
				Long avgComments) {
		}

		/** direction 'flat'은 변화 이내·비교 불가를 겸한다(계약 record 주석 참고) — was는 원값 그대로 전달. */
		public record Trend(String direction, Integer changePct, Long olderAvg, Long newerAvg) {
		}

		/** bars는 올린 순(posted_at ASC, short_code ASC) — 원 정렬 그대로 전달. */
		public record Chart(String metric, List<Bar> bars) {
		}

		public record Bar(
				String shortCode,
				OffsetDateTime postedAt,
				String contentType,
				Long views,
				Long likes,
				Long comments,
				Boolean sponsored) {
		}

		/** count DESC · label ASC — findCategoryStats의 정렬 그대로. */
		public record ContentMix(List<Category> categories) {
		}

		public record Category(String label, Long count) {
		}

		/**
		 * strip = chart.bars와 같은 순서의 sponsored 배열(프론트가 "올린 순서대로" 렌더).
		 * lastAdNote: lastAdPostedAt 없으면 null, 경과 0일 "마지막 광고 오늘", N일 "마지막 광고 N일 전"(was 표현 조립).
		 */
		public record Ads(
				Long sponsoredCount,
				List<Boolean> strip,
				OffsetDateTime lastAdPostedAt,
				String lastAdNote,
				Comparison comparison) {
		}

		/** organicAvg·adAvg 둘 다 있어야 존재 — Ads.comparison 자체가 null이 되는 조건은 어셈블러가 판정. */
		public record Comparison(
				String metric,
				Long organicCount,
				Long adCount,
				Long organicAvg,
				Long adAvg,
				Integer adDropPct) {
		}

		/** lastUploadDaysAgo·isActive(14일 미만)는 was 표현 조립(C1 스펙 §3 말미). */
		public record Activity(
				OffsetDateTime lastPostedAt,
				Long lastUploadDaysAgo,
				boolean isActive,
				BigDecimal avgIntervalDays) {
		}
	}
}
