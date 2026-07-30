package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.List;

/** app.monitoring_registrations 1행 + 건별 처리 결과(입력 순서 보존, 6.28). */
public record RegistrationRow(long id, long userId, OffsetDateTime requestedAt, OffsetDateTime completedAt,
		List<RegistrationEntryRow> entries) {
}
