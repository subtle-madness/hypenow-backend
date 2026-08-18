package com.celfit.monitoring.store;

import java.time.LocalDate;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * profile_meta 테이블 접점 — 계정 단위 최신 1행 upsert(계약 §3 profile_meta).
 * 스냅샷과 달리 이력 없이 최신 값만 유지한다.
 */
@Repository
public class ProfileMetaRepository {

	private static final Logger log = LoggerFactory.getLogger(ProfileMetaRepository.class);

	private final JdbcTemplate db;

	public ProfileMetaRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * lastUploadedAt이 null이면(열거 0건·taken_at 전부 미상) 기존 값을 덮지 않는다 — 게시물 열거가
	 * 없었을 뿐인데 "최근 게시일 없음"으로 보이면 안 되므로 COALESCE(EXCLUDED, 기존값) 패턴을 쓴다.
	 * display_name도 같은 보존 시맨틱이다(08-18): 프로필 응답의 full_name은 세션 편차로 빠질 수
	 * 있는데, 등록 경로는 단건 응답(owner full_name)이 먼저 채우고 프로필 콜이 뒤에 오므로
	 * 무조건 덮어쓰면 방금 채운 표시 이름이 null로 지워진다.
	 */
	public void upsert(String username, String displayName, String profileImageUrl, LocalDate lastUploadedAt) {
		String normalizedImageUrl = normalizeImageUrl(username, profileImageUrl);
		db.update("""
				INSERT INTO profile_meta (username, display_name, profile_image_url, last_uploaded_at, updated_at)
				VALUES (?, ?, ?, ?, now())
				ON CONFLICT (username) DO UPDATE SET
				  display_name = COALESCE(EXCLUDED.display_name, profile_meta.display_name),
				  -- 업스트림이 일시적으로 무효 스킴을 주면 정규화 결과가 null이 되는데, 그대로 덮으면
				  -- 기존 유효 이미지가 날아간다 — POST 모드(upsertOwnerFromPost)와 보존 시맨틱을 통일.
				  profile_image_url = COALESCE(EXCLUDED.profile_image_url, profile_meta.profile_image_url),
				  last_uploaded_at = COALESCE(EXCLUDED.last_uploaded_at, profile_meta.last_uploaded_at),
				  updated_at = now()""",
				username, displayName, normalizedImageUrl, lastUploadedAt);
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
		String normalizedImageUrl = normalizeImageUrl(username, profilePicUrl);
		db.update("""
				INSERT INTO profile_meta (username, display_name, profile_image_url, updated_at)
				VALUES (?, ?, ?, now())
				ON CONFLICT (username) DO UPDATE SET
				  display_name = COALESCE(EXCLUDED.display_name, profile_meta.display_name),
				  profile_image_url = COALESCE(EXCLUDED.profile_image_url, profile_meta.profile_image_url),
				  updated_at = now()""",
				username, fullName, normalizedImageUrl);
	}

	/**
	 * Hiker 업스트림이 이따금 {@code "exception://"} 같은 무효 스킴을 준다(실측: cherish__sy). http(s)가
	 * 아니면 null로 강등해 저장·서빙에 무효 URL이 흘러가지 않게 한다.
	 */
	private static String normalizeImageUrl(String username, String profileImageUrl) {
		if (profileImageUrl == null || profileImageUrl.isBlank()) {
			return null;
		}
		String lower = profileImageUrl.toLowerCase(Locale.ROOT);
		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			return profileImageUrl;
		}
		String excerpt = profileImageUrl.length() > 100 ? profileImageUrl.substring(0, 100) : profileImageUrl;
		log.warn("무효 스킴 profile_image_url 폐기: username={}, value={}", username, excerpt);
		return null;
	}
}
