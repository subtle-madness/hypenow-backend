package com.celfit.was.v1.brandmonitoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 브랜드 스코프 해시태그 facet 집계·필터 판정(스펙 2026-08-31). 키는
 * {@link BrandCaptionHashtags#normalize}, 노출 표기는 최빈 원문(동수는 첫 등장)이다.
 * PostRef.hashtags가 게시물 단위 dedup을 이미 보장하므로 count = 그 태그가 있는 게시물 수.
 */
public final class BrandHashtagFacets {

	/** facet 1행 — tag는 표시용 원문 표기, count는 게시물 수. public인 이유: 응답 meta에 실려 Jackson 직렬화 대상. */
	public record Entry(String tag, long count) {
	}

	private BrandHashtagFacets() {
	}

	static List<Entry> of(List<BrandPostAssembler.PostRef> refs) {
		Map<String, Long> postCountByKey = new LinkedHashMap<>();
		Map<String, Map<String, Long>> rawFreqByKey = new HashMap<>();
		for (BrandPostAssembler.PostRef ref : refs) {
			for (String raw : ref.hashtags()) {
				String key = BrandCaptionHashtags.normalize(raw);
				postCountByKey.merge(key, 1L, Long::sum);
				rawFreqByKey.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(raw, 1L, Long::sum);
			}
		}
		List<Entry> entries = new ArrayList<>();
		postCountByKey.forEach((key, count) -> entries.add(new Entry(displayOf(rawFreqByKey.get(key)), count)));
		entries.sort((a, b) -> a.count() == b.count()
				? BrandCaptionHashtags.normalize(a.tag()).compareTo(BrandCaptionHashtags.normalize(b.tag()))
				: Long.compare(b.count(), a.count()));
		return entries;
	}

	/** 최빈 원문 표기(동수는 첫 등장 — LinkedHashMap 순회 순서가 보장). */
	private static String displayOf(Map<String, Long> rawFreq) {
		String best = null;
		long bestCount = -1;
		for (Map.Entry<String, Long> e : rawFreq.entrySet()) {
			if (e.getValue() > bestCount) {
				best = e.getKey();
				bestCount = e.getValue();
			}
		}
		return best;
	}

	static boolean matches(BrandPostAssembler.PostRef ref, String filterKey) {
		for (String raw : ref.hashtags()) {
			if (BrandCaptionHashtags.normalize(raw).equals(filterKey)) {
				return true;
			}
		}
		return false;
	}

	/** 요청 파라미터 → 정규화 필터 키. 앞의 # 허용, 공백·빈 값은 null(필터 미적용). */
	static String filterKey(String param) {
		if (param == null) {
			return null;
		}
		String s = param.strip();
		if (s.startsWith("#")) {
			s = s.substring(1);
		}
		return s.isBlank() ? null : BrandCaptionHashtags.normalize(s);
	}
}
