package com.celfit.monitoring.store;

import com.celfit.instagram.source.CommentInfo;
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

	/**
	 * 이번 수집분을 누적 upsert — 이전 id 보존, 재관측 id는 body·like_count·답글·시각만 갱신.
	 *
	 * <p><b>같은 댓글 재관측 시 null 관측의 덮어쓰기 보호(S6, CommentRepository 동형 결함,
	 * 2026-09-03 배포 전 감사 수정)</b> — self 댓글(DirectCommentFetcher)은 ownerReplyText가 항상
	 * null이고 like_count도 nullable(파싱 실패 시 null)이다. Hiker가 먼저 채운 작성자 답글·좋아요
	 * 수를 self 재관측이 무조건 덮어 지우던 활성 결함(fetchComments 운영 개통 중) — 둘 다 COALESCE로
	 * 보존한다.
	 *
	 * <p>like_count는 컬럼 자체가 NOT NULL이라 ON CONFLICT SET의 COALESCE만으로는 부족하다 —
	 * VALUES 튜플 자체가 null이면 충돌 판정 전에 제약 위반으로 실패한다(CommentRepository와 동일
	 * 실측). VALUES 절에서부터 {@code COALESCE(?, 기존 like_count 서브쿼리, 0)}으로 최종값을 미리
	 * 확정해 항상 non-null을 보장한다.
	 */
	@Transactional
	public void upsertForPost(String shortCode, List<CommentInfo> comments) {
		if (comments.isEmpty()) {
			return;
		}
		db.batchUpdate("""
				INSERT INTO brand_post_comment (short_code, id, author, body, like_count, commented_at, owner_reply_text)
				VALUES (?, ?, ?, ?,
				        COALESCE(?, (SELECT like_count FROM brand_post_comment WHERE short_code = ? AND id = ?), 0),
				        ?, ?)
				ON CONFLICT (short_code, id) DO UPDATE SET
					body = EXCLUDED.body,
					like_count = COALESCE(EXCLUDED.like_count, brand_post_comment.like_count),
					owner_reply_text = COALESCE(EXCLUDED.owner_reply_text, brand_post_comment.owner_reply_text),
					commented_at = EXCLUDED.commented_at""",
				comments, comments.size(), (ps, c) -> {
					ps.setString(1, shortCode);
					ps.setString(2, c.id());
					ps.setString(3, c.author());
					ps.setString(4, c.body());
					// like_count는 nullable — setLong(long)이면 null 언박싱에서 NPE가 난다.
					// setObject로 null을 그대로 넘겨야 COALESCE가 판단할 수 있다.
					ps.setObject(5, c.likeCount());
					ps.setString(6, shortCode);
					ps.setString(7, c.id());
					ps.setTimestamp(8, Timestamp.from(c.commentedAt()));
					ps.setString(9, c.ownerReplyText());
				});
	}
}
