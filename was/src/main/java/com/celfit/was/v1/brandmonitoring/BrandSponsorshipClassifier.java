package com.celfit.was.v1.brandmonitoring;

import java.util.List;

/** 협찬 판정(FE §4.4) — 조회 시 계산·저장 없음(캡션 원문이 있어 키워드 개선이 과거분에 즉시 소급). */
public final class BrandSponsorshipClassifier {

	private static final List<String> CONFIRM_KEYWORDS =
			List.of("#광고", "#협찬", "#유료광고", "유료 광고", "유료광고", "광고입니다", "협찬받", "협찬 받");

	private BrandSponsorshipClassifier() {}

	public static String classify(Boolean isPaidPartnership, String caption) {
		if (Boolean.TRUE.equals(isPaidPartnership)) {
			return "sponsored";
		}
		if (caption != null && CONFIRM_KEYWORDS.stream().anyMatch(caption::contains)) {
			return "sponsored";
		}
		return Boolean.FALSE.equals(isPaidPartnership) ? "organic" : "unknown";
	}
}
