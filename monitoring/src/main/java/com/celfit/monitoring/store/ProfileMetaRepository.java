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

	/**
	 * POST 등록분 전용 upsert(트랙 II) — 단건 응답(/v2/media/by/code) owner 필드로 채운다.
	 * POST 전용 계정은 {@link #upsert}의 계정 갈래(프로필 콜)를 영구히 타지 않아 이 경로가 없으면
	 * profile_meta 행 자체가 생기지 않는다.
	 *
	 * <p>{@link #upsert}와 달리 COALESCE로 기존 값을 지키는 이유: 단건 응답 셰이프에 owner 필드가
	 * 없을 때(null) 계정 갈래가 이미 채운 정상값을 덮어 지우면 안 된다 — 같은 계정에 ACCOUNT·POST
	 * 캠페인이 공존하면 같은 스윕에서 saveAccount(정본) 뒤에 savePost가 돌 수 있다.
	 *
	 * <p>last_uploaded_at은 컬럼 목록에서 아예 뺐다 — 단건 경로는 게시물 1건의 taken_at만 알 뿐
	 * 계정의 "최근 게시일"(열거 전체의 최댓값)을 알 방법이 없고, 여기서 손대면 계정 갈래가 채운
	 * 정확한 값을 부정확한 값으로 덮어쓰게 된다.
	 */
	public void upsertOwnerFromPost(String username, String fullName, String profilePicUrl) {
		db.update("""
				INSERT INTO profile_meta (username, display_name, profile_image_url, updated_at)
				VALUES (?, ?, ?, now())
				ON CONFLICT (username) DO UPDATE SET
				  display_name = COALESCE(EXCLUDED.display_name, profile_meta.display_name),
				  profile_image_url = COALESCE(EXCLUDED.profile_image_url, profile_meta.profile_image_url),
				  updated_at = now()""",
				username, fullName, profilePicUrl);
	}
}
