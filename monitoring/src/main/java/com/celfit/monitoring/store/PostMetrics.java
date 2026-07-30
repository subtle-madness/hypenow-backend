package com.celfit.monitoring.store;

/**
 * post_snapshot 지표 6종 + content_type — 직전 스냅샷과의 비교(지표 비공개 판정)에만 쓴다.
 * 취득 불가 지표는 null이다(피드 조회수 등 — 계약 §3 null 규칙).
 */
public record PostMetrics(String contentType, Long likes, Long comments, Long views,
		Long saves, Long shares, Long reposts) {}
