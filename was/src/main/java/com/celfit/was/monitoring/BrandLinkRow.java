package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/**
 * app.brand_monitorings 1행 — user↔브랜드 연결(2026-08-07 스펙 §3-1). brandId는 monitoring
 * brand_account.id 논리 참조(크로스 DB FK 없음). deletedAt이 채워진 행은 해제된 과거 연결이고,
 * 활성 조회(findActive*)는 항상 deletedAt IS NULL만 돌려준다.
 */
public record BrandLinkRow(long id, long userId, long brandId, String username,
		OffsetDateTime createdAt, OffsetDateTime deletedAt) {
}
