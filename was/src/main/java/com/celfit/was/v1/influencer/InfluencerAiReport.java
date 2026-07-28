package com.celfit.was.v1.influencer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 스펙 6.5 인플루언서 AI 리포트 응답 v2 (07-27 개편) — 카피 없음(account_analyses 미생성/구 스키마)
 * 이어도 블록 구조는 유지하고 카피 필드만 null.
 * 유효 팔로워·성장세·헤드라인은 알고리즘 산출(LLM 아님) — 산식은 Assembler 참조.
 */
public record InfluencerAiReport(String tagline, Long analyzedCount, Long totalPosts,
		/** 피어 표본 3계정 미만이거나 계정/피어 ER 근거가 없으면 null(화면은 칸 숨김). */
		Long effectiveFollowers,
		/** effectiveFollowers와 같은 조건에서만 값을 가짐 — 산출 불가 시 null. */
		Integer effectiveFollowersPct,
		Stats stats, Chart chart, ContentMix contentMix, Ads ads, Activity activity) {

	/** 전체/광고 2행 × 4지표. ad 행은 광고 게시물이 없으면 null. */
	public record Stats(String metric, BigDecimal viewsPerFollower,
			/** account_analyses 카피 3종 중 하나 — 미생성/구 스키마 계정은 null. */
			String perfSummary,
			StatRow overall, StatRow ad) {
		public record StatRow(Stat views, Stat er, Stat likes, Stat comments) {
		}
		/** value: views·likes·comments는 정수, er은 퍼센트(소수 1). growthPct(성장세)·topPct(피어 퍼센타일,
		 *  피어 3계정 미만이면 항상 null)는 산출 불가 시 null. */
		public record Stat(BigDecimal value, Integer growthPct, Integer topPct) {
		}
	}

	/** bars가 추이 그래프·게시물 차트·광고 스트립·브랜드별 게시물 패널의 단일 소스. */
	public record Chart(String metric, List<Bar> bars) {
		public record Bar(Long views, Long likes, Long comments, String postedAt,
				Boolean sponsored, String contentType, String caption, String thumbnailUrl,
				/** 캡션 분류(ad_type='sponsored') 콘텐츠의 detected_brands 첫 항목 — 비광고 게시물도
				 *  브랜드 언급이 있으면 채워질 수 있고, 다중 브랜드 협업이면 대표성 보장 안 됨. null 가능. */
				String brand) {
		}
	}

	public record ContentMix(
			/** account_analyses 카피 3종 중 하나 — 미생성/구 스키마 계정은 null. */
			String contentSummary,
			List<Category> categories, List<String> traits) {
		public record Category(String label, Long count) {
		}
	}

	public record Ads(
			/** account_analyses 카피 3종 중 하나 — 미생성/구 스키마 계정은 null. */
			String adSummary,
			Long sponsoredCount, List<Boolean> strip, String lastAdNote,
			/** 스팬일수/(건수-1). 광고 2건 미만이거나 전부 같은 날(스팬 0)이면 null. */
			BigDecimal adIntervalDays,
			Long lastAdDaysAgo,
			/** 사실값 템플릿(알고리즘 산출, LLM 아님) — 광고 이력 없으면(lastAdDaysAgo null) null. */
			String headline,
			List<Brand> brands, List<Product> products) {
		public record Brand(String name, Long count) {
		}
		public record Product(String name, Long count) {
		}
	}

	public record Activity(Long lastUploadDaysAgo, Boolean isActive, BigDecimal avgIntervalDays) {
	}
}
