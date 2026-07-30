package com.celfit.monitoring.store;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import java.time.LocalDate;
import java.util.Optional;
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

	/**
	 * 직전 스냅샷 — 지표 비공개 판정 기준. 같은 날 재수집은 upsert로 덮이므로 **그 이전 날짜**만 본다:
	 * 당일 행까지 포함하면 등록 직후 스윕처럼 하루에 두 번 들어오는 경로에서 자기 자신과 비교하게 된다.
	 */
	public Optional<PostMetrics> findLatestPostBefore(String shortCode, LocalDate on) {
		return db.query("""
				SELECT content_type, likes, comments, views, saves, shares, reposts
				FROM post_snapshot
				WHERE short_code = ? AND captured_on < ?
				ORDER BY captured_on DESC LIMIT 1""",
				(rs, i) -> new PostMetrics(rs.getString("content_type"),
						rs.getObject("likes", Long.class), rs.getObject("comments", Long.class),
						rs.getObject("views", Long.class), rs.getObject("saves", Long.class),
						rs.getObject("shares", Long.class), rs.getObject("reposts", Long.class)),
				shortCode, on)
				.stream().findFirst();
	}
}
