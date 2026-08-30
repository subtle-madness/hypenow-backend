package com.celfit.was.v1.monitoring;

/**
 * GET·PATCH /v1/notification-settings 응답(2026-08-27 주간 개편 §5) — 주간 리포트 메일 수신
 * 여부 한 개. 이벤트 종류별 4토글 매트릭스는 폐지됐다(FE 통지 필요).
 */
public record NotificationSettingsResponse(boolean weeklyEmail) {
}
