package com.celfit.was.v1.brandmonitoring;

import java.util.Comparator;
import java.util.List;

/**
 * 브랜드 게시물 서빙(2026-09-02 노출 상한 폐지 설계 §4).
 *
 * <p>구(2026-08-28까지): 수집 개수 상한의 서빙 컷. 브랜드 게시물 모수를 소비하는 표면들이
 * <b>같은 상한·같은 순서</b>로 자르게 하는 단일 지점이었다.
 *
 * <p>현(2026-09-02~): 신선도 통제가 <b>수집 쪽 롤링 세트로 이동</b>했다 — 서빙은 창 안 전량을
 * 반환한다. 컷은 FE 계약({@code meta.collectionCapped}) 호환으로 유지하되, 폐지 후에는 항상
 * false다.
 */
final class BrandCollectionCap {

	private BrandCollectionCap() {
	}

	/**
	 * 서빙 상한 — 수집 개수 상한({@code collection-post-limit:2000})과 같은 값이었다. 2026-09-02
	 * 노출 상한 폐지로 더 이상 서빙 컷에는 쓰이지 않는다(F6 주석 정정 — 정렬은 이 상수가 아니라
	 * {@link #UPLOADED_DESC}가 참조한다). 지금은 <b>{@code meta.limit} 표기 전용</b>이다 —
	 * V1BrandPostsController가 FE 계약 호환을 위해 응답 메타에 이 값을 그대로 내려보낸다.
	 */
	static final int POST_LIMIT = 2000;

	/**
	 * 최신 업로드순 <b>전순서</b> — 업로드일 내림차순(미상 마지막) + shortcode 타이브레이크다.
	 * 2026-09-02 노출 상한 폐지로 서빙에서 자르는 용도로 더 이상 쓰이지 않지만,
	 * V1BrandPostsController 정렬 로직이 참조한다.
	 */
	static final Comparator<BrandPostAssembler.PostRef> UPLOADED_DESC = Comparator
			.comparing(BrandPostAssembler.PostRef::uploadedOn,
					Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(BrandPostAssembler.PostRef::shortcode);

	/**
	 * 컷 결과.
	 *
	 * @param refs 상한 통과분 — 이후의 모든 계산(counts·facets·필터·정렬·집계·페이지)이 보는 모수다.
	 * @param capped 컷 폐지(2026-09-02) 후에는 <b>항상 false</b>다(F6 주석 정정) — 서빙에서는 더 이상
	 *     상한에 걸리지 않는다. 필드 자체는 FE 계약({@code meta.collectionCapped}) 호환을 위해 남아
	 *     있을 뿐, 실질 판정으로 쓰이지 않는다.
	 */
	record Capped(List<BrandPostAssembler.PostRef> refs, boolean capped) {
	}

	/**
	 * 링크 표시 창을 통과한 refs를 전량 반환한다.
	 *
	 * <p>신선도 통제가 수집 쪽 롤링 세트로 이동했다(2026-09-02 감시 세트 설계 §4) — 서빙은 창 안
	 * 전량이다. {@code capped}는 컷 폐지로 항상 false를 반환한다 — 필드 자체는 FE 계약
	 * ({@code meta.collectionCapped}) 호환으로만 남긴다.
	 */
	static Capped apply(List<BrandPostAssembler.PostRef> windowed) {
		// 노출 컷 폐지(2026-09-02 감시 세트 설계 §4) — 신선도 통제가 수집 쪽 롤링 세트로 옮겨가
		// 서빙은 창 안 전량이다. capped 필드는 FE 계약(meta.collectionCapped) 호환으로 남긴다.
		return new Capped(windowed, false);
	}
}
