package com.celfit.monitoring.store;

/**
 * 만료 스윕이 종결시킨 캠페인 1건 — 수집 종료 알람의 재료.
 * userId는 V3 이전 등록분에서 null일 수 있다(그 캠페인은 알람에서 제외된다).
 */
public record ExpiredTarget(long id, Long userId, String username, String trackedShortCode) {}
