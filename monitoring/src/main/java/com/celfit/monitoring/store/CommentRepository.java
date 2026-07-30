package com.celfit.monitoring.store;

import com.celfit.monitoring.hiker.CommentInfo;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * post_comment 테이블 접점 — 게시물당 전량 교체 갱신(계약 §3 post_comment).
 * DELETE + batch INSERT를 한 트랜잭션으로 묶어 교체 도중 부분 상태가 보이지 않게 한다.
 */
@Repository
public class CommentRepository {

	private final JdbcTemplate db;

	public CommentRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** 이전 댓글을 지우고 이번 수집분으로 통째로 바꾼다 — 개별 upsert가 아니라 전량 교체(계약 §3). */
	@Transactional
	public void replaceForPost(String shortCode, List<CommentInfo> comments) {
		db.update("DELETE FROM post_comment WHERE short_code = ?", shortCode);
		if (comments.isEmpty()) {
			return;   // batchSize 0 호출을 피한다 — 삭제만으로 "댓글 0건"이 이미 반영된 상태다.
		}
		db.batchUpdate("""
				INSERT INTO post_comment (short_code, id, author, body, like_count, commented_at, owner_reply_text)
				VALUES (?, ?, ?, ?, ?, ?, ?)""",
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
