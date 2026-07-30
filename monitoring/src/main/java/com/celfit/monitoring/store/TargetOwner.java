package com.celfit.monitoring.store;

/**
 * 어떤 게시물을 추적 중인 캠페인과 그 수신자 — 지표 비공개 알람의 수신자 해석용.
 * 스냅샷은 게시물 단위라 캠페인 간 공유된다: 같은 게시물을 여러 캠페인이 추적하면 각자 알람을 받는다.
 */
public record TargetOwner(long targetId, Long userId, String username) {}
