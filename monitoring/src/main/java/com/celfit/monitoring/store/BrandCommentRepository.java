package com.celfit.monitoring.store;

import com.celfit.monitoring.hiker.CommentInfo;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * brand_post_comment 접점 — 게시물 전역 누적 합집합({@link CommentRepository}와 같은 계약:
 * 행을 삭제하지 않는다). 전면 전용 스키마 결정(08-06)으로 캠페인 post_comment와 분리 —
 * 겹침 게시물(캠페인 추적 + 브랜드 태그)은 댓글이 양쪽에 각각 쌓인다(수용된 비용).
 */
@Repository
public class BrandCommentRepository {

	private final JdbcTemplate db;

	public BrandCommentRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** 게시물의 기존 댓글 id 집합 — 댓글 수집의 기지 페이지 중단 판정용(태그 스펙 §3). */
	public Set<String> findIds(String shortCode) {
		return new HashSet<>(db.queryForList(
				"SELECT id FROM brand_post_comment WHERE short_code = ?", String.class, shortCode));
	}

	/** 이번 수집분을 누적 upsert — 이전 id 보존, 재관측 id는 body·like_count·답글·시각만 갱신. */
	@Transactional
	public void upsertForPost(String shortCode, List<CommentInfo> comments) {
		if (comments.isEmpty()) {
			return;
		}
		db.batchUpdate("""
				INSERT INTO brand_post_comment (short_code, id, author, body, like_count, commented_at, owner_reply_text)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (short_code, id) DO UPDATE SET
					body = EXCLUDED.body,
					like_count = EXCLUDED.like_count,
					owner_reply_text = EXCLUDED.owner_reply_text,
					commented_at = EXCLUDED.commented_at""",
				comments, comments.size(), (ps, c) -> {
					ps.setString(1, shortCode);
					ps.setString(2, c.id());
					ps.setString(3, c.author());
					ps.setString(4, c.body());
					ps.setLong(5, c.likeCount());
					ps.setTimestamp(6, Timestamp.from(c.commentedAt()));
					ps.setString(7, c.ownerReplyText());
				});
	}
}
