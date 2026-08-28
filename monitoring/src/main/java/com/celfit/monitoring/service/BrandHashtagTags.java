package com.celfit.monitoring.service;

import java.util.regex.Pattern;

/**
 * 해시태그 유효 문자 검증(2026-08-28 축소 — 계정명 유도 태그 시드가 was로 일원화되며 monitoring의
 * derive()는 죽은 코드가 됐다: was {@code BrandHashtagTags#derive}가 같은 규칙으로 유도한 태그를
 * 링크 생성 시 일반 태그 add로 push하고, monitoring은 그 결과를 검증만 한다). 남은 책임은 유저
 * 입력 태그 관리 API(GET/PUT/POST/DELETE hashtag-tags)의 유효 문자 검증뿐이다.
 */
public final class BrandHashtagTags {

	/** IG 해시태그 허용 문자 — 글자(한글 포함)·숫자·언더스코어. 점(.)은 태그를 끊는다. */
	private static final Pattern VALID_TAG = Pattern.compile("[\\p{L}\\p{N}_]+");

	private BrandHashtagTags() {
	}

	/**
	 * 유저 입력 태그 관리 API 전용(2026-08-12) — was의 자동 유도(계정명 접두사 절삭)와 달리,
	 * 유저가 직접 입력한 태그는 잘라내지 않고 전체 일치로 거부한다(조용한 절삭은 유저가 의도한
	 * 태그와 실제 저장된 태그가 달라지는 사고를 유발한다).
	 */
	public static boolean isValidTag(String tag) {
		return VALID_TAG.matcher(tag).matches();
	}
}
