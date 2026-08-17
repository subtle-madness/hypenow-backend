package com.celfit.was.v1.brandmonitoring;

import java.util.Set;

/**
 * 브랜드 수집 범위 값 공간(FE 요청서 2026-08-12) — 등록 요청 검증·기본값의 단일 정의.
 * CHECK 제약과 monitoring 검증이 같은 집합을 이중 방어한다.
 *
 * <p>값은 <b>두 곳에 저장된다</b>(2026-08-17 개정) — 규칙이 서로 다르니 혼동하지 말 것:
 * <ul>
 *   <li><b>자산 레벨</b>({@code monitoring brand_account.collection_months}) = 실제 크롤 창.
 *       브랜드를 공유하는 유저 간 max이고 <b>절대 줄지 않는다</b>(수집한 사실이 정본).</li>
 *   <li><b>링크 레벨</b>({@code app.brand_monitorings.collection_months}) = 유저별 표시 창.
 *       명시한 값을 그대로 저장하므로 <b>축소도 반영된다</b>(생략하면 불변). 게시물 목록·counts·
 *       상세·계정 응답의 {@code collectionMonths}가 이 값을 따른다.</li>
 * </ul>
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
