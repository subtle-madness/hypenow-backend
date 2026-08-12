package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/**
 * app.brand_monitorings 1행 — user↔브랜드 연결(2026-08-07 스펙 §3-1). brandId는 monitoring
 * brand_account.id 논리 참조(크로스 DB FK 없음). deletedAt이 채워진 행은 해제된 과거 연결이고,
 * 활성 조회(findActive*)는 항상 deletedAt IS NULL만 돌려준다.
 *
 * <p>{@code accountType}은 이 관계의 속성이다(own/competitor, 08-12) — 같은 브랜드라도 유저마다
 * 다를 수 있어 brand_account가 아니라 여기 있다. 값 공간은 {@code BrandAccountType}.
 */
public record BrandLinkRow(long id, long userId, long brandId, String username, String accountType,
		OffsetDateTime createdAt, OffsetDateTime deletedAt) {
}
