package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetType;
import java.time.Instant;

/**
 * 등록 명령 — 계약 §2-1 요청의 서비스 층 표현.
 * ACCOUNT는 username·keywordRule, POST는 shortCode만 쓴다(반대쪽 필드는 무시).
 */
public record RegisterCommand(String registrationKey, TargetType type, String username,
		String shortCode, KeywordRule keywordRule, Instant expiresAt) {}
