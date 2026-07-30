package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/**
 * monitoring DB target 1행(계약 §3, v2.2) — status·fail_reason 어휘는 monitoring이 확정, 해석 없이 전달.
 *
 * <p>v2.2 P1 확장 4종: {@code userId}(알람 수신자, V3 이전 등록분은 null), {@code trackedHiddenAt}
 * (추적 대상 접근 불가 감지 시각 — TRACKING 유지한 채 세팅, 재공개 시 null 복귀), {@code fetchFailing}
 * (재시도 소진 일시 오류 표시 — 성공 시 false 복귀), {@code matchedKeywords}(감지 자동 전환 시 실제
 * 매칭된 키워드 배열 jsonb — POST 직접 등록·감지 전 WATCHING은 null, was는 null이면 빈 배열로 폴백).
 */
public record TargetRow(long id, String type, String username, String shortCode,
		String keywordRule, String status, String trackedShortCode, OffsetDateTime trackedSince,
		String registrationKey, OffsetDateTime expiresAt, OffsetDateTime registeredAt,
		OffsetDateTime closedAt, OffsetDateTime lastFetchedAt, String failReason,
		Long userId, OffsetDateTime trackedHiddenAt, boolean fetchFailing, String matchedKeywords) {
}
