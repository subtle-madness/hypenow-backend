package com.celfit.monitoring.store;

import com.celfit.instagram.source.CommentInfo;
import java.sql.Timestamp;
import java.util.List;
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

	/**
	 * 이번 수집분을 누적 upsert한다 — 이전 id는 보존되고 새 id는 추가되며, 같은 id를 재관측하면
	 * body·like_count·owner_reply_text·commented_at만 갱신된다(위 클래스 계약 참고).
	 *
	 * <p><b>같은 댓글 재관측 시 null 관측의 덮어쓰기 보호(S6, 2026-09-03 배포 전 감사 수정)</b> —
	 * self 댓글(DirectCommentFetcher)은 ownerReplyText가 항상 null이다(로그아웃 GraphQL에
	 * preview_child_comments가 없음). like_count도 nullable(자체 응답 파싱 실패 시 null,
	 * DirectCommentFetcher 주석). fetchComments는 운영에서 이미 켜져 있어(24.5만 건이 위험 모수),
	 * Hiker가 먼저 채운 작성자 답글·좋아요 수를 self 재관측이 무조건 덮어 지우던 활성 결함이었다.
	 *
	 * <p>like_count는 컬럼 자체가 NOT NULL이라(스키마 변경 없이 고치는 게 이 수정의 전제) ON
	 * CONFLICT SET의 COALESCE만으로는 부족하다 — INSERT가 시도하는 VALUES 튜플 자체가 이미 null이면
	 * 충돌 판정 전에 NOT NULL 위반으로 실패한다(실측: setLong(long) 언박싱 NPE를 걷어내고 나면 이
	 * 제약 위반이 다음 장벽으로 드러난다). VALUES 절에서부터
	 * {@code COALESCE(?, 기존 like_count 서브쿼리, 0)}으로 최종값을 미리 확정해 항상 non-null을
	 * 보장한다 — 기존 행이 있으면 그 값을, 없으면(정말 처음 보는 댓글인데 like_count까지 실패) 0으로
	 * 안전 폴백한다.
	 */
	@Transactional
	public void upsertForPost(String shortCode, List<CommentInfo> comments) {
		if (comments.isEmpty()) {
			return;   // batchSize 0 호출을 피한다 — 이번 수집분이 0건이면 upsert할 것이 없다.
		}
		db.batchUpdate("""
				INSERT INTO post_comment (short_code, id, author, body, like_count, commented_at, owner_reply_text)
				VALUES (?, ?, ?, ?,
				        COALESCE(?, (SELECT like_count FROM post_comment WHERE short_code = ? AND id = ?), 0),
				        ?, ?)
				ON CONFLICT (short_code, id) DO UPDATE SET
					body = EXCLUDED.body,
					like_count = COALESCE(EXCLUDED.like_count, post_comment.like_count),
					owner_reply_text = COALESCE(EXCLUDED.owner_reply_text, post_comment.owner_reply_text),
					commented_at = EXCLUDED.commented_at""",
				comments, comments.size(), (ps, c) -> {
					ps.setString(1, shortCode);
					ps.setString(2, c.id());
					ps.setString(3, c.author());
					ps.setString(4, c.body());
					// like_count는 nullable(위 메서드 주석 참고) — setLong(long)이면 null 언박싱에서
					// NPE가 난다. setObject로 null을 그대로 넘겨야 COALESCE가 판단할 수 있다.
					ps.setObject(5, c.likeCount());
					ps.setString(6, shortCode);
					ps.setString(7, c.id());
					ps.setTimestamp(8, Timestamp.from(c.commentedAt()));
					ps.setString(9, c.ownerReplyText());
				});
	}
}
