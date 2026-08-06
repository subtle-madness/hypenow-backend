package com.celfit.monitoring.store;

import com.celfit.monitoring.hiker.AuthorInfo;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * author_profile 접점 — 게시자 프로필 전역 캐시(브랜드 간 공유, 태그 스펙 §6).
 * 이력 없이 최신 1행 upsert, 30일 stale 판정은 fetched_at 기준(스펙 §8 — 등장 시 갱신).
 */
@Repository
public class AuthorProfileRepository {

	private final JdbcTemplate db;

	public AuthorProfileRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** is_verified는 관측값 그대로 — 키 부재(null)를 미인증(false)으로 굳히지 않는다(계약 §3-2). */
	public void upsert(AuthorInfo a) {
		db.update("""
				INSERT INTO author_profile (ig_user_id, username, full_name, followers, following,
				                            media_count, biography, profile_pic_url, is_private,
				                            is_verified, fetched_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (ig_user_id) DO UPDATE SET
				  username = EXCLUDED.username, full_name = EXCLUDED.full_name,
				  followers = EXCLUDED.followers, following = EXCLUDED.following,
				  media_count = EXCLUDED.media_count, biography = EXCLUDED.biography,
				  profile_pic_url = EXCLUDED.profile_pic_url, is_private = EXCLUDED.is_private,
				  is_verified = EXCLUDED.is_verified, fetched_at = now()""",
				a.igUserId(), a.username(), a.fullName(), a.followers(), a.following(),
				a.mediaCount(), a.biography(), a.profilePicUrl(), a.isPrivate(), a.isVerified());
	}

	/**
	 * 신선한(콜 불필요) id 집합 — fetched_at ≥ staleBefore인 보유 행만 반환한다.
	 * 콜 필요분 = 후보 − 반환값(미보유·stale 모두 포함) — 호출자(BrandCollectService)가 차집합을 만든다.
	 */
	public Set<String> freshIgUserIds(Collection<String> igUserIds, Instant staleBefore) {
		if (igUserIds.isEmpty()) {
			return Set.of();
		}
		String placeholders = String.join(",", Collections.nCopies(igUserIds.size(), "?"));
		Object[] args = new Object[igUserIds.size() + 1];
		int i = 0;
		for (String id : igUserIds) {
			args[i++] = id;
		}
		args[i] = Timestamp.from(staleBefore);
		return new HashSet<>(db.queryForList(
				"SELECT ig_user_id FROM author_profile WHERE ig_user_id IN (" + placeholders
						+ ") AND fetched_at >= ?",
				String.class, args));
	}
}
