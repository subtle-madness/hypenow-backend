package com.celfit.monitoring.store;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 스냅샷 테이블 접점 — 관측 대상 단위로 하루 1행(캠페인 수와 무관).
 * 같은 날 재수집은 덮어쓴다(마지막 관측이 그날의 값).
 */
@Repository
public class SnapshotRepository {

	private final JdbcTemplate db;

	public SnapshotRepository(JdbcTemplate db) {
		this.db = db;
	}

	public void upsertProfile(String username, LocalDate on, ProfileInfo p) {
		db.update("""
				INSERT INTO profile_snapshot (username, captured_on, followers, following, media_count)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (username, captured_on) DO UPDATE SET
				  followers=EXCLUDED.followers, following=EXCLUDED.following,
				  media_count=EXCLUDED.media_count""",
				username, on, p.followers(), p.following(), p.mediaCount());
	}

	/** 스냅샷은 지표만 담는다 — takenAt·캡션 같은 게시물 속성은 저장 대상이 아니다. */
	public void upsertPost(LocalDate on, PostInfo p) {
		db.update("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
				                           likes, comments, views, saves, shares, reposts)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (short_code, captured_on) DO UPDATE SET
				  likes=EXCLUDED.likes, comments=EXCLUDED.comments, views=EXCLUDED.views,
				  saves=EXCLUDED.saves, shares=EXCLUDED.shares, reposts=EXCLUDED.reposts""",
				p.username(), p.shortCode(), on, p.contentType(),
				p.likes(), p.comments(), p.views(), p.saves(), p.shares(), p.reposts());
	}
}
