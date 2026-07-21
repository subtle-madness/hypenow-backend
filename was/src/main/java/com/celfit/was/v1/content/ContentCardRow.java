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
		String handle,
		String displayName,
		String profileImageUrl,
		Long followers) {

	/** 카드 SELECT 절 공통 상수 — 목록(6.1)·recentContents(6.4) 리포지토리가 같이 쓴다.
	 *  아카이브된 이미지는 /img/ 상대경로(Vercel rewrite→오브젝트 스토리지), 미아카이브는 원본 CDN 폴백. */
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
			       a.handle, a.display_name,
			       COALESCE('/img/' || ip.object_path, a.profile_image_url) AS profile_image_url,
			       a.followers
			""";

	/** SELECT의 it·ip 별칭 공급 — 카드 FROM 절에 반드시 함께 붙인다. */
	public static final String IMAGE_JOINS = """
			LEFT JOIN image_assets it ON it.kind = 'thumbnail' AND it.key = c.short_code
			LEFT JOIN image_assets ip ON ip.kind = 'profile' AND ip.key = a.handle
			""";
}
