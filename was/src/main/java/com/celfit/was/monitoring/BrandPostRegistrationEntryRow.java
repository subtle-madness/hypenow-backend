package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/**
 * app.brand_post_registration_entries 1행 — 브랜드 direct 등록 요청 내 입력 하나의 처리 결과
 * (입력 순서=seq). 레거시 {@link RegistrationEntryRow}와 달리 {@code kind}가 없다(브랜드 direct
 * 등록은 항상 게시물 링크만 받는다 — 계정 입력 없음) — {@code itemId} 대신 {@code shortCode}가
 * 매칭·표시 키다(레거시 아이템이 아예 생기지 않으므로).
 */
public record BrandPostRegistrationEntryRow(long registrationId, int seq, String input, String shortCode,
		String result, String reasonCode, String reason, OffsetDateTime settledAt) {
}
