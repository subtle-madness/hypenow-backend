package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/**
 * app.brand_monitorings 1행 — user↔브랜드 연결(2026-08-07 스펙 §3-1). brandId는 monitoring
 * brand_account.id 논리 참조(크로스 DB FK 없음). deletedAt이 채워진 행은 해제된 과거 연결이고,
 * 활성 조회(findActive*)는 항상 deletedAt IS NULL만 돌려준다.
 *
 * <p>{@code accountType}은 이 관계의 속성이다(own/competitor, 08-12) — 같은 브랜드라도 유저마다
 * 다를 수 있어 brand_account가 아니라 여기 있다. 값 공간은 {@code BrandAccountType}.
 *
 * <p>{@code collectionMonths}(2026-08-17)는 이 유저가 신청한 <b>표시 기간</b>이다(1|3|6|12) —
 * 크롤 자산 {@code brand_account.collection_months}(유저 간 max)와 별개로, 서빙 창을 자르는 기준.
 * 같은 브랜드라도 유저마다 다를 수 있어 accountType처럼 관계 속성으로 여기 있다.
 *
 * <p>{@code hashtagSeededAt}(2026-09-03 자동 시드 재설계 §4-1) — 이 <b>링크</b>에 자동 태그가
 * 반영된 시각. NULL이면 아직 미반영이라 다음 조회에서 훅({@code ensureAutoSeeded})이 돈다.
 * 브랜드 단위 계산 기록({@code app.brand_hashtag_seed})과 짝이다 — 계산은 브랜드당 1회,
 * 장부 삽입은 사용자당 1회. 이 값이 찍혀 있으면 사용자가 자동 태그를 지운 뒤 다시 조회해도
 * 되살아나지 않는다.
 */
public record BrandLinkRow(long id, long userId, long brandId, String username, String accountType,
		int collectionMonths, OffsetDateTime createdAt, OffsetDateTime deletedAt,
		OffsetDateTime hashtagSeededAt) {
}
