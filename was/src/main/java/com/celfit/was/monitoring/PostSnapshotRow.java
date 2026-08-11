package com.celfit.was.monitoring;

import java.time.LocalDate;

/**
 * post_snapshot 1행 — 취득 불가 지표는 null(피드 조회수 등, 계약 §3 null 규칙).
 * likesHidden은 좋아요 수 숨김 관측(계약 §3 v2.3) — likes null이 "숨김"인지 구분하는 유일한 신호.
 * sharesHidden은 공유 횟수 숨김 관측(계약 §3 v2.7) — shares null의 같은 구분 신호.
 */
public record PostSnapshotRow(LocalDate capturedOn, String contentType, Long likes, boolean likesHidden,
		Long comments, Long views, Long saves, Long shares, boolean sharesHidden, Long reposts) {
}
