package com.celfit.was.v1.brandmonitoring;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 계정명 해시태그 1종 유도(2026-08-27 태그 장부 갭 수정) — monitoring
 * {@code com.celfit.monitoring.service.BrandHashtagTags#derive}의 <b>규칙 복제본</b>이다.
 * monitoring이 브랜드 등록·replay 때 {@code brand_hashtag}에 심는 자동 태그와 같은 값을 was의
 * 사용자 태그 원장({@code app.brand_hashtag_tags})에도 남기기 위해 필요하다 — 원장이 비면
 * 해시태그 게시물의 사용자 격리 필터(내 태그 ∩ 게시물 매칭 태그)가 아무것도 통과시키지 못한다.
 *
 * <p>모듈 간 Java 공유는 계약 모듈({@code contract-analysis})만 허용되므로 복제가 정본 관용구다
 * ({@code V1BrandAccountService.normalizeTag}가 monitoring {@code normalizeTagItem}을 복제한 것과 동형).
 * <b>규칙을 바꾸면 monitoring 쪽도 같이 바꿔야 한다</b> — 두 벌이 갈리면 장부와 스윕 대상이 어긋난다.
 *
 * <p>brandName(회사명) 유도는 하지 않는다 — monitoring이 2026-08-17에 자동 시드를 3종에서 계정명
 * 1종으로 축소하면서 brandName을 시드에서 뺐다. 여기서만 심으면 monitoring이 스윕하지 않는 태그가
 * 장부에 남고, 다음 PUT의 합집합 계산이 그 태그를 monitoring으로 되밀어 그 결정을 되돌린다.
 */
public final class BrandHashtagTags {

	/**
	 * 사용자 1명이 브랜드 1개에 둘 수 있는 감지 해시태그 상한(2026-09-03, FE 피드백 09-01 #4-A) —
	 * FE가 화면에서 막던 6개를 서버가 검증한다(초과 시 400 {@code HASHTAG_TAG_LIMIT_EXCEEDED}).
	 * 모수는 <b>이 사용자의 장부</b>({@code app.brand_hashtag_tags}, 자동 시드 계정명 태그 포함) —
	 * 같은 브랜드에 연결된 다른 사용자의 태그는 세지 않는다(사용자 스코프, 08-19 개정과 동일 단위).
	 */
	public static final int MAX_TAGS_PER_USER = 6;

	/** IG 해시태그 허용 문자 — 글자(한글 포함)·숫자·언더스코어. 점(.)은 태그를 끊는다. */
	private static final Pattern VALID_TAG = Pattern.compile("[\\p{L}\\p{N}_]+");

	private BrandHashtagTags() {
	}

	/**
	 * 계정명 해시태그 1종(원소 0~1개) 유도 — username을 소문자·strip한 뒤 IG 해시태그 실동작(첫
	 * 무효 문자에서 잘림 — 예: cclime.beauty → cclime)에 맞춰 선행 유효 접두사만 취한다. 접두사가
	 * 없으면(무효 문자로 시작) 빈 집합을 반환한다.
	 *
	 * @param username 필수. null이면 NPE(등록 경로는 항상 정규화된 계정명을 넘긴다).
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
}
