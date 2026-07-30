package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetType;
import java.time.Instant;

/**
 * 등록 명령 — 계약 v2 §2-1 요청의 서비스 층 표현.
 * ACCOUNT는 username·keywordRule, POST는 shortCode만 쓴다(반대쪽 필드는 무시).
 * userId는 타입 무관 필수 — 알람 수신자 해석의 유일한 근거다.
 */
public record RegisterCommand(String registrationKey, TargetType type, Long userId, String username,
		String shortCode, KeywordRule keywordRule, Instant expiresAt) {}
