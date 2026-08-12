package com.celfit.was.v1.brandmonitoring;

import java.util.Set;

/**
 * 브랜드 수집 범위 값 공간(FE 요청서 2026-08-12) — 등록 요청 검증·기본값의 단일 정의.
 * 값은 자산 레벨(monitoring brand_account.collection_months)로 저장되고 절대 줄지 않는다
 * (공유 유저 간 max — 스펙 결정 요약). CHECK 제약과 monitoring 검증이 같은 집합을 이중 방어한다.
 */
public final class BrandCollectionMonths {

	public static final int DEFAULT = 12;

	private static final Set<Integer> ALLOWED = Set.of(1, 3, 6, 12);

	private BrandCollectionMonths() {
	}

	/** null은 12로 접는다(하위 호환 — collectionMonths 없는 기존 요청 본문은 현행 12개월 그대로). */
	public static int orDefault(Integer months) {
		return months == null ? DEFAULT : months;
	}

	public static boolean isValid(int months) {
		return ALLOWED.contains(months);
	}
}
