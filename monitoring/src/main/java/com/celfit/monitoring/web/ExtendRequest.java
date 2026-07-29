package com.celfit.monitoring.web;

import java.time.OffsetDateTime;

/**
 * 기간 연장 요청 본문 — 계약 §2-4 `{ "expiresAt": "2026-09-30T23:59:59+09:00" }`.
 * 오프셋 포함 ISO-8601(등록과 같은 형식) — 필수·미래 시각 검증은 서비스가 한다.
 */
public record ExtendRequest(OffsetDateTime expiresAt) {}
