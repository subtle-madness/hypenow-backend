package com.celfit.monitoring.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 해시태그 감지 태그 셋 유도(2026-08-17 축소 — FE 협의로 제외 문자열 기능 폐기와 함께 자동 시드가
 * 3종에서 1종으로 줄었다). 과거엔 브랜드명(company_name)·계정명 루트(상용 접미사 제거)·전체
 * 계정명 3종을 유도했지만, "루트"는 원래 제외 문자열 기본값이자 태그 후보 하나의 재료였을 뿐이라
 * 제외 문자열이 사라지며 개념 자체가 쓸모를 잃었다 — 남은 유도는 계정명 그대로 하나뿐이다.
 */
public final class BrandHashtagTags {

	/** IG 해시태그 허용 문자 — 글자(한글 포함)·숫자·언더스코어. 점(.)은 태그를 끊는다. */
	private static final Pattern VALID_TAG = Pattern.compile("[\\p{L}\\p{N}_]+");

	private BrandHashtagTags() {
	}

	/**
	 * 계정명 해시태그 1종(원소 0~1개) 유도 — username을 소문자·strip한 뒤 IG 해시태그 실동작(첫
	 * 무효 문자에서 잘림 — 예: cclime.beauty → cclime)에 맞춰 선행 유효 접두사만 취한다. 접두사가
	 * 없으면(무효 문자로 시작) 빈 집합을 반환한다.
	 *
	 * @param username 필수. null이면 NPE(등록 데이터엔 항상 존재하는 필드라 호출측 방어 불요).
	 */
	public static LinkedHashSet<String> derive(String username) {
		String u = username.toLowerCase(Locale.ROOT).strip();
		LinkedHashSet<String> tags = new LinkedHashSet<>();
		String prefix = leadingValidPrefix(u);
		if (!prefix.isBlank()) {
			tags.add(prefix);
		}
		return tags;
	}

	/** 문자열 시작 지점부터 이어지는 최장 유효 해시태그 문자 구간(없으면 빈 문자열). */
	private static String leadingValidPrefix(String s) {
		Matcher m = VALID_TAG.matcher(s);
		return m.lookingAt() ? m.group() : "";
	}

	/**
	 * 유저 입력 태그 관리 API 전용(2026-08-12) — 전자동 유도(derive)는 무효 문자를 잘라내지만,
	 * 유저가 직접 입력한 태그는 잘라내지 않고 전체 일치로 거부한다(조용한 절삭은 유저가 의도한
	 * 태그와 실제 저장된 태그가 달라지는 사고를 유발한다).
	 */
	public static boolean isValidTag(String tag) {
		return VALID_TAG.matcher(tag).matches();
	}
}
