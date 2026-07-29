package com.celfit.monitoring.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 키워드 규칙 3종 목록 — 매칭 = (and 전부) ∧ (any 비었거나 하나 이상) ∧ (exclude 전무).
 * 부분 문자열·대소문자 무시·캡션 전문 대상(해시태그도 캡션의 일부라 자연 포함).
 */
public record KeywordRule(List<String> and, List<String> any, List<String> exclude) {

	public KeywordRule {
		// null·공백 원소는 제거 — 특히 ""는 contains("")가 항상 true라 전 캡션 매칭을 유발한다.
		// 원소가 전부 걸러져 목록이 비면 isValid()=false가 되어 등록 API가 거부한다.
		and = sanitize(and);
		any = sanitize(any);
		exclude = sanitize(exclude);
	}

	private static List<String> sanitize(List<String> keywords) {
		return keywords == null
				? List.of()
				: keywords.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).toList();
	}

	/** and·any 중 최소 한 목록은 비어 있지 않아야 등록 가능. */
	public boolean isValid() {
		return !and.isEmpty() || !any.isEmpty();
	}

	public boolean matches(String caption) {
		if (caption == null || !isValid()) {
			return false;
		}
		String c = caption.toLowerCase(Locale.ROOT);
		if (!and.stream().allMatch(k -> c.contains(k.toLowerCase(Locale.ROOT)))) {
			return false;
		}
		if (!any.isEmpty() && any.stream().noneMatch(k -> c.contains(k.toLowerCase(Locale.ROOT)))) {
			return false;
		}
		return exclude.stream().noneMatch(k -> c.contains(k.toLowerCase(Locale.ROOT)));
	}
}
