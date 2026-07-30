package com.celfit.monitoring.store;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * profile_meta 테이블 접점 — 계정 단위 최신 1행 upsert(계약 §3 profile_meta).
 * 스냅샷과 달리 이력 없이 최신 값만 유지한다.
 */
@Repository
public class ProfileMetaRepository {

	private final JdbcTemplate db;

	public ProfileMetaRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * lastUploadedAt이 null이면(열거 0건·taken_at 전부 미상) 기존 값을 덮지 않는다 — 게시물 열거가
	 * 없었을 뿐인데 "최근 게시일 없음"으로 보이면 안 되므로 COALESCE(EXCLUDED, 기존값) 패턴을 쓴다.
	 */
	public void upsert(String username, String displayName, String profileImageUrl, LocalDate lastUploadedAt) {
		db.update("""
				INSERT INTO profile_meta (username, display_name, profile_image_url, last_uploaded_at, updated_at)
				VALUES (?, ?, ?, ?, now())
				ON CONFLICT (username) DO UPDATE SET
				  display_name = EXCLUDED.display_name,
				  profile_image_url = EXCLUDED.profile_image_url,
				  last_uploaded_at = COALESCE(EXCLUDED.last_uploaded_at, profile_meta.last_uploaded_at),
				  updated_at = now()""",
				username, displayName, profileImageUrl, lastUploadedAt);
	}
}
