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
 * post_comment 테이블 접점.
 *
 * <p><b>계약</b>: post_comment는 "지금까지 관측된 top-15 댓글의 누적 합집합"이며 행을 삭제하지
 * 않는다. IG에서 삭제된 댓글은 남는다 — Hiker 응답 정렬이 IG 랭킹 혼합(시간순이 아님)이고
 * comment-pages=1(1페이지, 최대 15건)만 받으므로 "이번 응답에 없음"이 삭제를 뜻하는지 판정할
 * 방법이 없기 때문이다. 어설픈 삭제 추정보다 데이터 보존을 택한다 — 이 이유로 DELETE를 되살리지
 * 말 것.
 */
@Repository
public class CommentRepository {

	private final JdbcTemplate db;

	public CommentRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** 게시물의 기존 댓글 id 집합 — 브랜드 태그 모니터링 댓글 수집의 기지 중단 판정용(태그 스펙 §3). */
	public Set<String> findIds(String shortCode) {
		return new HashSet<>(db.queryForList(
				"SELECT id FROM post_comment WHERE short_code = ?", String.class, shortCode));
	}

	/**
	 * 이번 수집분을 누적 upsert한다 — 이전 id는 보존되고 새 id는 추가되며, 같은 id를 재관측하면
	 * body·like_count·owner_reply_text·commented_at만 갱신된다(위 클래스 계약 참고).
	 */
	@Transactional
	public void upsertForPost(String shortCode, List<CommentInfo> comments) {
		if (comments.isEmpty()) {
			return;   // batchSize 0 호출을 피한다 — 이번 수집분이 0건이면 upsert할 것이 없다.
		}
		db.batchUpdate("""
				INSERT INTO post_comment (short_code, id, author, body, like_count, commented_at, owner_reply_text)
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
