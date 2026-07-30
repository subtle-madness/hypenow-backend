package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * app.monitoring_registrations 1행 + 건별 처리 결과(입력 순서 보존, 6.28).
 *
 * <p>{@code trackingDays}·{@code campaignId}(V17)는 등록 요청 시점 값의 보존본이다 — share 링크
 * 항목은 접수 시점에 monitoring_items 행을 만들지 못해(§실행기) 해소 후 뒤늦게 같은 값을 써야
 * 하는데, 항목 자체가 없으니 요청 헤더에서 가져온다. 둘 다 이 마이그레이션 이전 행에서는 null일 수 있다.
 *
 * <p>{@code acknowledgedAt}은 확인(읽음) 처리 시각이다(프론트 요청, 알림 다이제스트 readAt과 동일한
 * 패턴) — 프론트가 localStorage로 임시 처리하던 확인 상태를 서버로 옮긴 필드.
 */
public record RegistrationRow(long id, long userId, OffsetDateTime requestedAt, OffsetDateTime completedAt,
		Integer trackingDays, Long campaignId, OffsetDateTime acknowledgedAt, List<RegistrationEntryRow> entries) {
}
