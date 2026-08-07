package com.celfit.was.v1.brandmonitoring;

import java.util.List;

/** 협찬 판정(FE §4.4) — 조회 시 계산·저장 없음(캡션 원문이 있어 키워드 개선이 과거분에 즉시 소급). */
public final class BrandSponsorshipClassifier {

	/** 판정 값 공간 — 소비처(어셈블러·필터·counts)가 리터럴을 다시 적지 않게 상수로 노출한다. */
	public static final String SPONSORED = "sponsored";
	public static final String ORGANIC = "organic";
	public static final String UNKNOWN = "unknown";

	private static final List<String> CONFIRM_KEYWORDS =
			List.of("#광고", "#협찬", "#유료광고", "유료 광고", "유료광고", "광고입니다", "협찬받", "협찬 받");

	private BrandSponsorshipClassifier() {}

	public static String classify(Boolean isPaidPartnership, String caption) {
		if (Boolean.TRUE.equals(isPaidPartnership)) {
			return SPONSORED;
		}
		if (caption != null && CONFIRM_KEYWORDS.stream().anyMatch(caption::contains)) {
			return SPONSORED;
		}
		return Boolean.FALSE.equals(isPaidPartnership) ? ORGANIC : UNKNOWN;
	}
}
