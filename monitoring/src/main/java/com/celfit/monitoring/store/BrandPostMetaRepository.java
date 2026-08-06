package com.celfit.monitoring.store;

import java.time.LocalDate;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * brand_post_meta 접점 — 게시물 전역 최신 1행 upsert({@link PostMetaRepository} 동형,
 * 전면 전용 스키마 결정 08-06). 썸네일 CDN 서명(~4일 만료)은 매일 스윕 upsert가 자동 방어.
 */
@Repository
public class BrandPostMetaRepository {

	private static final Logger log = LoggerFactory.getLogger(BrandPostMetaRepository.class);

	private final JdbcTemplate db;

	public BrandPostMetaRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * thumbnailUrl이 null이면(일시적 미취득) 기존 값을 덮지 않는다 — COALESCE(EXCLUDED, 기존값) 패턴.
	 * caption은 항상 EXCLUDED로 덮는다(수정 반영). first_seen_at은 갱신 안 함(최초 관측 보존).
	 * 무효 스킴 URL은 저장 전 null로 강등(PostMetaRepository·트랙 KK 동형).
	 */
	public void upsert(String shortCode, String username, String contentType, LocalDate uploadedAt,
			String caption, String thumbnailUrl) {
		String normalizedThumbnailUrl = normalizeThumbnailUrl(shortCode, thumbnailUrl);
		db.update("""
				INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url, first_seen_at)
				VALUES (?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (short_code) DO UPDATE SET
				  username = EXCLUDED.username,
				  content_type = EXCLUDED.content_type,
				  uploaded_at = EXCLUDED.uploaded_at,
				  caption = EXCLUDED.caption,
				  thumbnail_url = COALESCE(EXCLUDED.thumbnail_url, brand_post_meta.thumbnail_url)""",
				shortCode, username, contentType, uploadedAt, caption, normalizedThumbnailUrl);
	}

	private static String normalizeThumbnailUrl(String shortCode, String thumbnailUrl) {
		if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
			return null;
		}
		String lower = thumbnailUrl.toLowerCase(Locale.ROOT);
		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			return thumbnailUrl;
		}
		String excerpt = thumbnailUrl.length() > 100 ? thumbnailUrl.substring(0, 100) : thumbnailUrl;
		log.warn("무효 스킴 thumbnail_url 폐기: shortCode={}, value={}", shortCode, excerpt);
		return null;
	}
}
