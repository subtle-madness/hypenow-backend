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
	 * 팔로워 1회 수집(트랙 II 후속) — 해당 계정에 profile_snapshot 행이 하나라도 있는지 판정.
	 * POST 등록분만 있는 계정은 계정 갈래를 영구히 안 타 이 행이 계속 없다 — DailySweepJob이
	 * 이 메서드로 "아직 안 채워졌으면 1회만" 여부를 판단한다.
	 */
	public boolean hasProfileSnapshot(String username) {
		Boolean exists = db.queryForObject(
				"SELECT EXISTS(SELECT 1 FROM profile_snapshot WHERE username = ?)", Boolean.class, username);
		return Boolean.TRUE.equals(exists);
	}

	/**
	 * 직전 스냅샷 — 지표 비공개 판정 기준. 호출 시점이 그날 upsert **직전**이므로 당일 행이 있다면
	 * 그게 바로 "직전 관측"이다(오늘 두 번째 이후 수집) — **당일 포함**(`<=`)으로 조회해야 한다.
	 * `<`로 당일을 건너뛰면 같은 날 두 번째 수집이 어제 값과 다시 비교돼 이미 적재한 METRICS_HIDDEN을
	 * 또 적재한다(리뷰 I1 — 오늘 값이 아직 없는 그날 첫 수집에서는 당일 행 자체가 없으니 결과가 같다).
	 */
	public Optional<PostMetrics> findLatestPostUpTo(String shortCode, LocalDate on) {
		return db.query("""
				SELECT content_type, likes, comments, views, saves, shares, reposts
				FROM post_snapshot
				WHERE short_code = ? AND captured_on <= ?
				ORDER BY captured_on DESC LIMIT 1""",
				(rs, i) -> new PostMetrics(rs.getString("content_type"),
						rs.getObject("likes", Long.class), rs.getObject("comments", Long.class),
						rs.getObject("views", Long.class), rs.getObject("saves", Long.class),
						rs.getObject("shares", Long.class), rs.getObject("reposts", Long.class)),
				shortCode, on)
				.stream().findFirst();
	}
}
