package com.celfit.was.v1.admin;

import java.time.OffsetDateTime;

/**
 * 어드민 모니터링 활성 행 1건(설계 2026-08-01 §4 AdminMonitoringHealthService 공용 산출물) —
 * GET /v1/admin/users의 health와 GET /v1/admin/monitoring/registrations의 rows가 같은 모집단·
 * 같은 상태 유도를 쓰도록 이 record가 둘의 유일한 공통 산지다. userName·campaignName은 여기 없다
 * (배치 조회 Java 결합은 호출부 몫 — 이 record는 유저 하나만 볼 때도, 전체 유저를 볼 때도 재사용된다).
 */
public record AdminMonitoringRow(long itemId, long userId, Long campaignId, String status, String handle,
		String contentType, OffsetDateTime lastCollectedAt, String postUrl) {
}
