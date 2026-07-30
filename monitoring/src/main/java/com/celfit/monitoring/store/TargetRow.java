package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import java.time.Instant;

/**
 * target 테이블 한 행 — 캠페인 단위 등록 정보.
 * userId는 was 유저의 논리 참조이자 알람 수신자다 — V3 이전에 등록된 행은 null이고, 그 캠페인의
 * 알람 이벤트는 적재되지 않는다(수신자 불명 — {@code AlarmRecorder}가 warn만 남기고 건너뛴다).
 * keywordRule은 ACCOUNT 전용이라 POST 등록 행에서는 null이다.
 * registeredAt은 감지 하한선이다 — 이 시각 이후에 게시된 것만 후보가 된다(설계 §5, 07-29 확정).
 */
public record TargetRow(long id, Long userId, TargetType type, String username, String shortCode,
		KeywordRule keywordRule, TargetStatus status, String trackedShortCode,
		String registrationKey, Instant expiresAt, String failReason, Instant registeredAt) {}
