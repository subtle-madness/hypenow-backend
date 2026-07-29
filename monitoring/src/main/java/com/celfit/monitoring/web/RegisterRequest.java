package com.celfit.monitoring.web;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.service.RegisterCommand;
import java.time.OffsetDateTime;

/**
 * 등록 요청 본문 — 계약 §2-1 그대로.
 * expiresAt은 오프셋 포함 ISO-8601(예: 2026-08-28T23:59:59+09:00) — 필수 필드 검증은 서비스가 한다.
 */
public record RegisterRequest(String registrationKey, TargetType type, String username,
		String shortCode, KeywordRule keywordRule, OffsetDateTime expiresAt) {

	public RegisterCommand toCommand() {
		return new RegisterCommand(registrationKey, type, username, shortCode, keywordRule,
				expiresAt == null ? null : expiresAt.toInstant());
	}
}
