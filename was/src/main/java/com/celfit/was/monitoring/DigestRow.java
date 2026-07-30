package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * app.monitoring_digests 1행(v3, V15, 6.32). itemsJson은 jsonb 원문을 ::text로 받은 것 —
 * 파싱은 호출부(v1 응답 조립, DigestResponse)의 몫이다.
 */
public record DigestRow(long id, long userId, LocalDate digestDate, String itemsJson, OffsetDateTime createdAt,
		OffsetDateTime readAt) {
}
