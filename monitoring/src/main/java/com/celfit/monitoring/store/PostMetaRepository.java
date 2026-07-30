package com.celfit.monitoring.store;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * post_meta 테이블 접점 — 게시물 단위 최신 1행 upsert(계약 §3 post_meta).
 * profile_meta와 같은 관용구: 스냅샷과 달리 이력 없이 최신 값만 유지한다.
 */
@Repository
public class PostMetaRepository {

	private final JdbcTemplate db;

	public PostMetaRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * thumbnailUrl이 null이면(일시적 미취득) 기존 값을 덮지 않는다 — COALESCE(EXCLUDED, 기존값) 패턴.
	 * caption은 항상 EXCLUDED로 덮는다(수정 반영 — 계약 §3). first_seen_at은 갱신 안 함(최초 관측 보존).
	 */
	public void upsert(String shortCode, String username, String contentType, LocalDate uploadedAt,
			String caption, String thumbnailUrl) {
		db.update("""
				INSERT INTO post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url, first_seen_at)
				VALUES (?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (short_code) DO UPDATE SET
				  username = EXCLUDED.username,
				  content_type = EXCLUDED.content_type,
				  uploaded_at = EXCLUDED.uploaded_at,
				  caption = EXCLUDED.caption,
				  thumbnail_url = COALESCE(EXCLUDED.thumbnail_url, post_meta.thumbnail_url)""",
				shortCode, username, contentType, uploadedAt, caption, thumbnailUrl);
	}
}
