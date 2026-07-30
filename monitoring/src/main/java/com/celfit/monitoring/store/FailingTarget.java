package com.celfit.monitoring.store;

/**
 * 일시 수집 오류(재시도 라운드 소진)로 fetch_failing이 false→true로 전이된 캠페인 1건 —
 * FETCH_FAILED 알람의 재료(계약 §3 alarm_event CONTENT_UNAVAILABLE 발화 시점).
 * {@link TargetRepository#markFetchFailing}은 이 전이가 실제로 일어난 행만 돌려준다 —
 * 이미 failing이던 target은 반환되지 않아 알람이 재발화하지 않는다.
 */
public record FailingTarget(long id, Long userId, String username, String trackedShortCode) {}
