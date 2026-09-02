package com.celfit.was.v1.brandmonitoring;

import java.time.LocalDate;

/**
 * 브랜드 게시물 창 판정(2026-08-27 서버 필터·패싯 설계) — 링크 표시 창(유저가 신청한
 * {@code collection_months})과 업로드 기간 필터의 순수 정적 규칙만 모은다. 목록·상세 컨트롤러가
 * 각자 들고 있던 private 3함수를 옮긴 것이라 동작은 불변이고, 서버 필터·패싯이 같은 판정을 재사용해야
 * 하기에 한곳으로 모았다 — 규칙이 두 벌이 되면 "목록엔 없는데 패싯 숫자에는 잡히는" 불일치가 난다.
 */
public final class BrandPostWindows {

	private BrandPostWindows() {
	}

	/** 링크 표시 창의 하한. */
	static LocalDate linkWindowStart(LocalDate today, int collectionMonths) {
		return today.minusMonths(collectionMonths);
	}

	/** 업로드 기간 필터(양끝 포함) — 업로드일 미상은 판정 불가라 제외한다(수집 전 직접 등록분). */
	static boolean withinUploadWindow(LocalDate uploadedOn, LocalDate from, LocalDate to) {
		if (from == null && to == null) {
			return true;
		}
		if (uploadedOn == null) {
			return false;
		}
		return (from == null || !uploadedOn.isBefore(from)) && (to == null || !uploadedOn.isAfter(to));
	}

	/**
	 * 링크 창 판정(2026-08-17) — direct는 유저가 URL을 명시 등록한 추적 대상이라 창과 무관하게
	 * 통과한다(창은 태그 수집 범위의 개념). 나머지는 기간 필터와 같은 판정이라 그쪽에 위임한다
	 * (업로드일 미상 제외 규칙의 정의가 {@link #withinUploadWindow} 한 곳에만 있게).
	 */
	static boolean withinLinkWindow(BrandPostAssembler.PostRef ref, LocalDate windowStart) {
		return BrandPostAssembler.SOURCE_DIRECT.equals(ref.source())
				|| withinUploadWindow(ref.uploadedOn(), windowStart, null);
	}
}
