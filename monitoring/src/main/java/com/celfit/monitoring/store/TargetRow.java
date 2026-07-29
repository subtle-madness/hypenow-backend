package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import java.time.Instant;

/**
 * target 테이블 한 행 — 캠페인 단위 등록 정보.
 * keywordRule은 ACCOUNT 전용이라 POST 등록 행에서는 null이다.
 */
public record TargetRow(long id, TargetType type, String username, String shortCode,
		KeywordRule keywordRule, TargetStatus status, String trackedShortCode,
		String registrationKey, Instant expiresAt, String failReason) {}
