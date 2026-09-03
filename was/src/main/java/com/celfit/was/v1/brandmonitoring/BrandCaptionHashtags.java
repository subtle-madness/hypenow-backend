package com.celfit.was.v1.brandmonitoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 캡션 해시태그 추출(스펙 2026-08-31 §3). 규칙은 ASCII # + [\p{L}\p{N}_]+ — 인스타 링크화와
 * 일치가 계약이다(전각 ＃ 제외·이모지 갭 수용, 검증 근거는 스펙). 문자 집합은 monitoring
 * {@code HashtagCandidateExtractor}·{@code BrandHashtagTags.isValidTag}와 같은 정의를 유지할 것 —
 * 갈리면 "화면에서 필터되는 태그"와 "monitoring이 제안·스윕하는 태그"가 어긋난다.
 */
public final class BrandCaptionHashtags {

	private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]+)");

	private BrandCaptionHashtags() {
	}

	/** 등장 순서 유지, 정규화 키 기준 dedup(값은 첫 등장 원문 표기). */
	public static List<String> extract(String caption) {
		if (caption == null || caption.isEmpty()) {
			return List.of();
		}
		LinkedHashMap<String, String> firstByKey = new LinkedHashMap<>();
		Matcher m = HASHTAG.matcher(caption);
		while (m.find()) {
			firstByKey.putIfAbsent(normalize(m.group(1)), m.group(1));
		}
		return List.copyOf(firstByKey.values());
	}

	/** 집계·필터 키 — 인스타 태그는 대소문자 무시(#OliveYoung = #oliveyoung). */
	public static String normalize(String tag) {
		return tag.toLowerCase(Locale.ROOT);
	}
}
