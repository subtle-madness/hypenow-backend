package com.celfit.monitoring.store;

import com.celfit.monitoring.hiker.PostInfo;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * brand_tagged_post 접점 — 브랜드 윈도우의 게시물 링크 + 댓글 게이트 상태.
 * 지표·메타·댓글 본문은 기존 공용 테이블(post_snapshot·post_meta·post_comment)에 있다.
 */
@Repository
public class TaggedPostRepository {

	private final JdbcTemplate db;

	public TaggedPostRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** 이 브랜드가 확보한 전체 code — 감지 신규 판정용(윈도우 이탈분 포함: 재유입 시 신규 아님). */
	public Set<String> knownCodes(long brandId) {
		return new HashSet<>(db.queryForList(
				"SELECT short_code FROM brand_tagged_post WHERE brand_id = ?", String.class, brandId));
	}

	/** 신규 감지 게시물 링크 — 재감지(ON CONFLICT)는 무해하게 무시한다. taken_at null은 호출자가 거른다. */
	public void insert(long brandId, PostInfo post) {
		db.update("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id, taken_at)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (brand_id, short_code) DO NOTHING""",
				brandId, post.shortCode(), post.username(), post.ownerUserId(),
				Timestamp.from(Instant.ofEpochSecond(post.takenAt())));
	}

	/** 댓글 게이트 저장값 배치 조회(IN절 1쿼리) — 열거 comment_count가 이 값보다 클 때만 댓글 콜. */
	public Map<String, Long> commentsCollectedCounts(long brandId, Collection<String> codes) {
		if (codes.isEmpty()) {
			return Map.of();
		}
		String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
		Object[] args = new Object[codes.size() + 1];
		args[0] = brandId;
		int i = 1;
		for (String code : codes) {
			args[i++] = code;
		}
		Map<String, Long> out = new HashMap<>();
		db.query("SELECT short_code, comments_collected_count FROM brand_tagged_post WHERE brand_id = ? AND short_code IN ("
						+ placeholders + ")",
				rs -> {
					out.put(rs.getString("short_code"), rs.getLong("comments_collected_count"));
				}, args);
		return out;
	}

	public void updateCommentsCollected(long brandId, String shortCode, long count) {
		db.update("""
				UPDATE brand_tagged_post SET comments_collected_count = ?
				WHERE brand_id = ? AND short_code = ?""", count, brandId, shortCode);
	}

	/** 티어 판정 입력 행 — 판정 자체는 BrandCrawlPolicy 순수 함수가 한다(스펙 §3). */
	public record TrackedPost(String shortCode, Instant takenAt, Instant lastCrawledAt) {}

	/** 추적 범위(taken_at ≥ minTakenAt) 링크 전부 — 스윕의 열거 깊이 결정 입력(스펙 §4). */
	public List<TrackedPost> trackedPosts(long brandId, Instant minTakenAt) {
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ? AND taken_at >= ?""",
				(rs, i) -> {
					Timestamp last = rs.getTimestamp("last_crawled_at");
					return new TrackedPost(rs.getString("short_code"),
							rs.getTimestamp("taken_at").toInstant(),
							last == null ? null : last.toInstant());
				}, brandId, Timestamp.from(minTakenAt));
	}

	/** 이번 열거에서 만난 게시물의 마지막 수집 시각 배치 갱신 — 다음 스윕의 티어 판정 입력. */
	public void touchCrawled(long brandId, Collection<String> codes, Instant at) {
		if (codes.isEmpty()) {
			return;
		}
		String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
		Object[] args = new Object[codes.size() + 2];
		args[0] = Timestamp.from(at);
		args[1] = brandId;
		int i = 2;
		for (String code : codes) {
			args[i++] = code;
		}
		db.update("UPDATE brand_tagged_post SET last_crawled_at = ? WHERE brand_id = ? AND short_code IN ("
				+ placeholders + ")", args);
	}
}
