package com.celfit.was.v1.brandmonitoring;

import java.util.Comparator;
import java.util.List;

/**
 * 수집 개수 상한의 서빙 컷(FE 요청 2026-08-28 ②) — 브랜드 게시물 모수를 소비하는 표면들이
 * <b>같은 상한·같은 순서</b>로 자르게 하는 단일 지점이다.
 *
 * <p>상한 자체는 수집 정책({@code monitoring.collection-post-limit:2000}, 2026-08-19 스펙)에서
 * 오고, 서빙은 "수집한 만큼 보여준다"를 지킨다. 컷 순서가 <b>요청 정렬과 무관하게 최신 업로드순</b>인
 * 이유도 거기 있다 — 이 상한은 "최근 2,000개까지 수집"이라는 정책의 표면이지 정렬별 상위 2,000이
 * 아니다.
 *
 * <p><b>이 클래스가 생긴 이유</b>: 08-28까지 컷은 {@code V1BrandPostsController}의 private 상수와
 * 인라인 스트림이었고, {@code /influencers}는 그 컷을 아예 타지 않았다. 그래서 목록이 "게시물 14개"로
 * 센 작성자를 눌러 들어가면 10개만 나왔다(실측 marynmay 관련 인원 2,800 vs 1,607, harthbeauty
 * 게시물 14 vs 10). 두 표면이 각자 컷을 들고 있으면 값이 갈리거나 — 값이 같아도 정렬 타이브레이크가
 * 갈리면 상한 경계에서 <b>서로 다른 2,000개</b>를 고른다. 컷을 쓰는 쪽이 늘면 여기만 부른다.
 */
final class BrandCollectionCap {

	private BrandCollectionCap() {
	}

	/**
	 * 서빙 상한 — 수집 개수 상한({@code collection-post-limit:2000})과 같은 값이다. 구 200은
	 * 90일·105건 시절의 값이라 정책 v1(365일 윈도우·저장소 상한 폐지) 이후 12개월치가 많은
	 * 브랜드(실측 463건)를 실제로 잘랐다.
	 */
	static final int POST_LIMIT = 2000;

	/**
	 * 최신 업로드순 <b>전순서</b> — 업로드일 내림차순(미상 마지막) + shortcode 타이브레이크다.
	 * 전순서라야 상한 경계와 페이지 경계가 요청마다 흔들리지 않는다(동률 구간에서 순서가 뒤집히면
	 * 페이지 간 중복·누락이 난다). 게시물 목록의 기본 정렬({@code uploaded_desc})이 같은 순서인
	 * 것은 우연이 아니다 — "최근 것부터"라는 한 규칙을 컷과 정렬이 공유한다.
	 */
	static final Comparator<BrandPostAssembler.PostRef> UPLOADED_DESC = Comparator
			.comparing(BrandPostAssembler.PostRef::uploadedOn,
					Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(BrandPostAssembler.PostRef::shortcode);

	/**
	 * 컷 결과.
	 *
	 * @param refs 상한 통과분 — 이후의 모든 계산(counts·facets·필터·정렬·집계·페이지)이 보는 모수다.
	 * @param capped 상한에 실제로 걸렸는지 — 전부 {@code POST_LIMIT}으로 통일하면 "상한에 걸림"과
	 *     "마침 정확히 2,000건"을 구분할 수 없어 별도 신호로 둔다({@code meta.collectionCapped}).
	 */
	record Capped(List<BrandPostAssembler.PostRef> refs, boolean capped) {
	}

	/**
	 * 링크 표시 창을 통과한 refs를 최신 업로드순 {@value #POST_LIMIT}으로 자른다.
	 *
	 * <p><b>호출 위치가 계약의 일부다</b> — 링크 창 필터 <b>뒤</b>, 업로드 기간(from/to)·분류 필터
	 * <b>앞</b>에서 부른다. 필터 뒤로 밀면 기간을 좁힐 때마다 상한 밖 게시물이 되살아나 화면 숫자가
	 * 필터에 따라 달라지고, 필터 앞에 두면 상한이 "수집 정책"이라는 고정된 의미를 유지한다.
	 * 계정이 여럿인 표면도 <b>계정 단위</b>로 부른다(상한이 브랜드 계정별 수집 정책이라서다).
	 */
	static Capped apply(List<BrandPostAssembler.PostRef> windowed) {
		if (windowed.size() <= POST_LIMIT) {
			return new Capped(windowed, false);
		}
		return new Capped(windowed.stream().sorted(UPLOADED_DESC).limit(POST_LIMIT).toList(), true);
	}
}
