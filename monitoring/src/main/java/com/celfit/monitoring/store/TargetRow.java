package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import java.time.Instant;

/**
 * target 테이블 한 행 — 캠페인 단위 등록 정보.
 * keywordRule은 ACCOUNT 전용이라 POST 등록 행에서는 null이다.
 * registeredAt은 감지 하한선이다 — 이 시각 이후에 게시된 것만 후보가 된다(설계 §5, 07-29 확정).
 */
public record TargetRow(long id, TargetType type, String username, String shortCode,
		KeywordRule keywordRule, TargetStatus status, String trackedShortCode,
		String registrationKey, Instant expiresAt, String failReason, Instant registeredAt) {}
