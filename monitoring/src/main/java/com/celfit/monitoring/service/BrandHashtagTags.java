package com.celfit.monitoring.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 해시태그 감지 태그 셋 유도(스펙 2026-08-11 §2) — 등록 데이터에서 전자동 3종:
 * #브랜드명(company_name, brand 유형만 — 판별은 was 소관, 여기엔 null로 옴) ·
 * #계정명 루트(상용 접미사 제거) · #전체 계정명. 유저 입력·시드 분석 없음.
 */
public final class BrandHashtagTags {

	/** IG 해시태그 허용 문자 — 글자(한글 포함)·숫자·언더스코어. 점(.)은 태그를 끊는다. */
	private static final Pattern VALID_TAG = Pattern.compile("[\\p{L}\\p{N}_]+");
	private static final List<String> SUFFIXES = List.of("official", "kr", "korea", "global");
	private static final int MIN_ROOT_LENGTH = 3;

	private BrandHashtagTags() {
	}

	/** 계정명 루트 — cclime_official → cclime. 접미사는 반복 제거하되 3자 미만이 되면 중단. */
	public static String root(String username) {
		String u = username.toLowerCase(Locale.ROOT).strip();
		boolean stripped = true;
		while (stripped) {
			stripped = false;
			for (String s : SUFFIXES) {
				for (String sep : List.of("_", ".")) {
					String suffix = sep + s;
					if (u.endsWith(suffix) && u.length() - suffix.length() >= MIN_ROOT_LENGTH) {
						u = u.substring(0, u.length() - suffix.length());
						stripped = true;
					}
				}
			}
		}
		return u;
	}

	/**
	 * 태그 셋 3종(순서 보존·중복 제거). brandName null·공백이면 2종.
	 * 각 후보는 IG 해시태그 실동작(첫 무효 문자에서 잘림 — 예: cclime.beauty → cclime)에 맞춰
	 * 선행 유효 접두사만 취하고, 접두사가 없으면(무효 문자로 시작) 후보를 버린다.
	 */
	public static LinkedHashSet<String> derive(String brandName, String username) {
		List<String> candidates = new ArrayList<>();
		if (brandName != null && !brandName.isBlank()) {
			candidates.add(brandName.strip().replaceAll("[\\s#]+", ""));
		}
		String u = username.toLowerCase(Locale.ROOT).strip();
		candidates.add(root(u));
		candidates.add(u);

		LinkedHashSet<String> tags = new LinkedHashSet<>();
		for (String candidate : candidates) {
			String prefix = leadingValidPrefix(candidate);
			if (!prefix.isBlank()) {
				tags.add(prefix);
			}
		}
		return tags;
	}

	/** 문자열 시작 지점부터 이어지는 최장 유효 해시태그 문자 구간(없으면 빈 문자열). */
	private static String leadingValidPrefix(String s) {
		Matcher m = VALID_TAG.matcher(s);
		return m.lookingAt() ? m.group() : "";
	}
}
