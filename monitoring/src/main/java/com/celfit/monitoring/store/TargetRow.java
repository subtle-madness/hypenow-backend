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
 * trackedHiddenAt·fetchFailing은 v2.2 hidden/error 신호(계약 §3) — status는 그대로 두고 이 둘로
 * 접근 불가·수집 오류를 표시한다. 스윕 자체는 "이미 hidden/failing인가"를 몰라도 동작한다
 * (전이 여부는 {@link TargetRepository#markHidden}·{@link TargetRepository#markFetchFailing}이
 * UPDATE 결과로 직접 판정) — 여기 있는 건 행 완결성 때문이다.
 */
public record TargetRow(long id, Long userId, TargetType type, String username, String shortCode,
		KeywordRule keywordRule, TargetStatus status, String trackedShortCode,
		String registrationKey, Instant expiresAt, String failReason, Instant registeredAt,
		Instant trackedHiddenAt, boolean fetchFailing) {}
