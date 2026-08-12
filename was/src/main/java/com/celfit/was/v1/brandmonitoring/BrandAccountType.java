package com.celfit.was.v1.brandmonitoring;

/**
 * 브랜드 구독 타입(2026-08-12 FE 요청) — 값 공간·타입별 상한·상한 초과 에러 코드의 단일 정의.
 * 타입은 계정이 아니라 유저-계정 관계의 속성이라 app.brand_monitorings에 저장된다
 * (같은 인스타 계정이 담당자에게는 own, 경쟁 브랜드 담당자에게는 competitor다).
 *
 * <p>enum이 아니라 상수+검증인 이유: 저장·응답·쿼리 파라미터가 전부 소문자 문자열이고
 * (JdbcClient·Jackson·normalizeFilter), 값 공간이 둘뿐이라 변환 계층이 순비용이다.
 */
public final class BrandAccountType {

	public static final String OWN = "own";
	public static final String COMPETITOR = "competitor";

	/** 내 브랜드 상한(FE 요청서 §2-4). */
	private static final int OWN_LIMIT = 6;
	/** 경쟁사 상한(FE 요청서 §2-4) — own보다 낮다. */
	private static final int COMPETITOR_LIMIT = 3;

	private BrandAccountType() {
	}

	public static boolean isValid(String type) {
		return OWN.equals(type) || COMPETITOR.equals(type);
	}

	/** null·빈 값은 own으로 접는다(하위 호환 — accountType 없는 기존 요청 본문). */
	public static String orDefault(String type) {
		return type == null || type.isBlank() ? OWN : type;
	}

	public static int limitOf(String type) {
		return COMPETITOR.equals(type) ? COMPETITOR_LIMIT : OWN_LIMIT;
	}

	/**
	 * 상한 초과 에러 코드 — own은 기존 코드를 그대로 쓴다(FE가 이미 그 문자열로 분기 중이라
	 * 바꾸면 배포된 등록 화면이 깨진다). competitor만 신설이다.
	 */
	public static String limitCodeOf(String type) {
		return COMPETITOR.equals(type) ? "COMPETITOR_ACCOUNT_LIMIT_REACHED" : "BRAND_ACCOUNT_LIMIT_REACHED";
	}

	/** 상한 초과 메시지 — 타입별 상한 값을 그대로 노출한다(FE가 안내 문구를 다시 만들지 않게). */
	public static String limitMessageOf(String type) {
		return COMPETITOR.equals(type)
				? "경쟁사는 최대 " + COMPETITOR_LIMIT + "개까지 등록할 수 있어요."
				: "내 브랜드는 최대 " + OWN_LIMIT + "개까지 등록할 수 있어요.";
	}

	public static int ownLimit() {
		return OWN_LIMIT;
	}

	public static int competitorLimit() {
		return COMPETITOR_LIMIT;
	}
}
