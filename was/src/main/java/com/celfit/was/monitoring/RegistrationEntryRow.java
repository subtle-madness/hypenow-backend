package com.celfit.was.monitoring;

/** app.monitoring_registration_entries 1행 — 등록 요청 내 입력 하나의 처리 결과(입력 순서=seq). */
public record RegistrationEntryRow(long registrationId, int seq, String input, String kind, String result,
		String reasonCode, String reason, String resolvedUrl, Long itemId) {
}
