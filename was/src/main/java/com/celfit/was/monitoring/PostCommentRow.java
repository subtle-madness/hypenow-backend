package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/**
 * post_comment 1행(계약 §3, v1.1) — shortCode를 동봉해 여러 게시물의 배치 조회 결과를 그룹핑할
 * 수 있게 한다(단일 게시물 조회 스키마와 달리 이 리포지토리 계층은 항상 배치). author는 원본
 * 핸들 그대로(마스킹은 어셈블러 책임). DB는 owner_reply_text를 제외한 전 컬럼이 NOT NULL이지만,
 * 방어적으로 nullable 필드로 받아 어셈블러가 결손 행을 통째로 제외할 수 있게 한다.
 */
public record PostCommentRow(String shortCode, String id, String author, String body, Long likeCount,
		OffsetDateTime commentedAt, String ownerReplyText) {
}
