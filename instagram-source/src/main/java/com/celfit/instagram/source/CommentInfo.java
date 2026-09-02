package com.celfit.instagram.source;

import java.time.Instant;

/**
 * 댓글 원재료 — post_comment 저장 단위(계약 §3).
 * ownerReplyText는 preview_child_comments 중 게시물 작성자 본인 답글(첫 건)의 본문 —
 * is_created_by_media_owner==true 이거나 답글 작성자 username이 게시물 소유 계정과
 * 대소문자 무시 일치하는 경우로 판정한다(findings §10-1). 없으면 null.
 */
public record CommentInfo(String id, String author, String body, Long likeCount, Instant commentedAt,
		String ownerReplyText) {}
