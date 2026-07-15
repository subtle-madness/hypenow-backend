package com.celfit.was.influencer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 인플루언서 상세 응답 — celfit-front AccountReport의 결정(비LLM) 데이터 + LLM 카피 7종.
 * profile = accounts ⊕ account_summaries, report = account_summaries + category_stats + content_series 조립.
 * LLM 카피 7종(tagline·summary·trend.note·chart.note·contentMix.traits·ads.headline·activity.paceNote)은
 * account_analyses 최신 1행의 C2 additive — 분석 이력이 없으면 전부 null(스펙 2026-07-14-c2-account-llm-design.md §1).
 * ads.brands 칩은 C2 범위 밖(캡션 분류 후속 태스크) — 필드 부재 유지.
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

	/**
	 * totalPosts = posts_count("전체 N개 중 최근 M개" 카피의 분모).
	 * tagline(프로필 헤더 개인화 한 줄)·summary(AI 분석 요약 문단)는 LLM 카피 — 분석 이력 없으면 null.
	 */
	public record Report(
			Long totalPosts,
			Long analyzedCount,
			String tagline,
			String summary,
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

		/**
		 * direction 'flat'은 변화 이내·비교 불가를 겸한다(계약 record 주석 참고) — was는 원값 그대로 전달.
		 * note = LLM 흐름 박스 문구(분석 이력 없으면 null).
		 */
		public record Trend(String direction, Integer changePct, Long olderAvg, Long newerAvg, String note) {
		}

		/** bars는 올린 순(posted_at ASC, short_code ASC) — 원 정렬 그대로 전달. note = LLM 차트 하단 캡션. */
		public record Chart(String metric, List<Bar> bars, String note) {
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

		/** count DESC · label ASC — findCategoryStats의 정렬 그대로. traits = LLM 성향 태그 칩 3~5개. */
		public record ContentMix(List<Category> categories, List<String> traits) {
		}

		public record Category(String label, Long count) {
		}

		/**
		 * strip = chart.bars와 같은 순서의 sponsored 배열(프론트가 "올린 순서대로" 렌더).
		 * lastAdNote: lastAdPostedAt 없으면 null, 경과 0일 "마지막 광고 오늘", N일 "마지막 광고 N일 전"(was 표현 조립).
		 * headline = LLM 광고 비교 헤드라인 — 광고 비교 데이터가 있을 때만 생성되므로 이력이 있어도 null 가능.
		 */
		public record Ads(
				Long sponsoredCount,
				List<Boolean> strip,
				OffsetDateTime lastAdPostedAt,
				String lastAdNote,
				String headline,
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

		/** lastUploadDaysAgo·isActive(14일 미만)는 was 표현 조립(C1 스펙 §3 말미). paceNote = LLM 활동 페이스 문구. */
		public record Activity(
				OffsetDateTime lastPostedAt,
				Long lastUploadDaysAgo,
				boolean isActive,
				BigDecimal avgIntervalDays,
				String paceNote) {
		}
	}
}
