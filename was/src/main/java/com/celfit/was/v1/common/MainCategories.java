package com.celfit.was.v1.common;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 대분류(slug) 검증 어휘 — 랭킹(6.1)·발굴(6.21) 쿼리 홀더가 공유한다(2026-08-31 F&B 서빙
 * 개방으로 공용화 — 그 전엔 두 곳이 각자 뷰티 7종을 하드코딩).
 *
 * <p>정본은 analysis DB의 beauty_taxonomy(생산자 소유, ARCHITECTURE §4-4)이고 여기는 검증용
 * 사본이다 — 어휘 추가(다음은 홈/리빙) 시 시드 마이그레이션과 함께 갱신할 것. DB에서 읽지 않는
 * 이유: 쿼리 홀더의 정적 검증 경로에 DB 의존을 넣는 구조 변경이 이득보다 크다(요청마다 조회
 * 또는 캐시 계층 신설). 축 판정(isFnb)은 발굴 게이트 분기의 재료라 slug 집합으로 충분하다.
 */
public final class MainCategories {

	/** 뷰티 축 — celfit-front 배포본 필터 7종 + 에스테틱(V20260809063533). */
	public static final Set<String> BEAUTY =
			Set.of("skincare", "suncare", "makeup", "cleansing", "haircare", "fragrance", "esthetic");

	/** F&B 축 — 피처링 트리 정본 6종(V20260831032411 시드). */
	public static final Set<String> FNB =
			Set.of("beverage", "alcohol", "convenience", "snack", "health-food", "recipe");

	/** 검증 allowlist — 두 축 합집합. */
	public static final Set<String> ALL = Stream.concat(BEAUTY.stream(), FNB.stream())
			.collect(Collectors.toUnmodifiableSet());

	/** vertical 파라미터 어휘 — 축 전체 조회(2026-09-01 FE 버티컬 요청). */
	public static final Set<String> VERTICALS = Set.of("beauty", "fnb");

	/** F&B 축 여부 — null(무필터)은 false(기본 화면 = 뷰티). */
	public static boolean isFnb(String mainCategory) {
		return mainCategory != null && FNB.contains(mainCategory);
	}

	private MainCategories() {
	}
}
