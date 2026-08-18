package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * app.brand_post_registrations 1행 + 건별 처리 결과(2026-08-18 direct 통합 §2-2-1) — 브랜드 direct
 * 등록 전용 저장소다. 레거시 {@link RegistrationRow}와 달리 {@code brandId}가 행 자체에 있다 —
 * share 해소분의 브랜드를 접수 시점 링크에서 추정할 필요가 없다(레거시의
 * {@code resolveLazyMappingBrand} 폴백이 통째로 불필요해지는 이유).
 */
public record BrandPostRegistrationRow(long id, long userId, long brandId, Long campaignId,
		OffsetDateTime requestedAt, OffsetDateTime completedAt, List<BrandPostRegistrationEntryRow> entries) {
}
