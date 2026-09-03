package com.celfit.was.v1.content;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 카드 조인 결과 1행 — contents ⋈ content_analyses ⋈ accounts (분석 결과끼리, §4-4 허용).
 * 목록(6.1)·recentContents(6.4)가 같은 행 형태를 공유한다.
 * 이 SELECT의 컬럼 alias ↔ 아래 컴포넌트가 1:1 — 컬럼을 바꾸면 둘을 같이 바꾼다.
 */
public record ContentCardRow(
		String shortCode,
		String thumbnailUrl,
		String caption,
		OffsetDateTime postedAt,
		String contentType,
		BigDecimal videoDuration,
		String originalUrl,
		Long views,
		Long likes,
		Long comments,
		Long hypeScore,
		OffsetDateTime metricCapturedAt,
		String mainCategory,
		String subCategoriesJson,
		String adType,
		String brandsJson,
		String productsJson,
		String distributorsJson,
		// 공동구매 판정(2026-09-03, 스펙 2026-09-03-group-purchase-judgment-design.md §6) — 서버 판정
		// 테이블 group_purchase_judgments.verdict를 그대로 싣는다. 미판정(행 없음·verdict NULL)은 false.
		boolean groupPurchase,
		String handle,
		String displayName,
		String profileImageUrl,
		Long followers,
		// 하입 스코어 소수점 노출(2026-07-30, 스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md
		// §10) — hypeScore(정수, 값·의미 불변)는 그대로 두고 표시·정렬은 이 필드로 옮긴다.
		BigDecimal hypeScorePrecise) {

	/** 카드 SELECT 절 공통 상수 — 목록(6.1)·recentContents(6.4) 리포지토리가 같이 쓴다.
	 *  아카이브된 이미지는 /img/ 상대경로(Vercel rewrite→오브젝트 스토리지), 미아카이브는 원본 CDN 폴백.
	 *  group_purchase 컬럼은 group_purchase_judgments LEFT JOIN(별칭 gpj — IMAGE_JOINS 또는 FROM 절에
	 *  직접 붙는 곳 모두 동반 필수)이 공급한다. */
	public static final String SELECT = """
			SELECT c.short_code,
			       COALESCE('/img/' || it.object_path, c.thumbnail_url) AS thumbnail_url,
			       c.caption, c.posted_at, c.content_type,
			       c.video_duration, c.original_url, c.views, c.likes, c.comments,
			       c.hype_score, c.metric_captured_at,
			       an.main_category, an.sub_categories::text AS sub_categories_json, an.ad_type,
			       an.detected_brands::text AS brands_json,
			       an.detected_products::text AS products_json,
			       an.detected_distributors::text AS distributors_json,
			       COALESCE(gpj.verdict, false) AS group_purchase,
			       a.handle, a.display_name,
			       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
			       a.followers, c.hype_score_precise
			""";

	/** SELECT의 it·ip·gpj 별칭 공급 — 카드 FROM 절에 반드시 함께 붙인다(gpj는 group_purchase 컬럼 재료). */
	public static final String IMAGE_JOINS = """
			LEFT JOIN image_assets it ON it.kind = 'thumbnail' AND it.key = c.short_code
			LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
			LEFT JOIN group_purchase_judgments gpj ON gpj.short_code = c.short_code
			""";
}
