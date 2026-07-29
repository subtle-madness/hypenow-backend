package com.celfit.analytics.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * trait 고정 어휘 스냅샷 (trait_taxonomy, V41 시드 — 정본은 2026-07-29 어휘 통제 스펙 부록 A).
 * 프롬프트 주입({@link #promptBlock()})과 저장 sanitize({@link #names()})가 같은 스냅샷을 본다.
 */
public record TraitTaxonomy(List<Entry> entries) {

	public record Entry(String name, String facet) {}

	/** 캐노니컬 전체 집합 — AccountAnalysisWriter sanitize·배치 매핑 검증용. */
	public Set<String> names() {
		return entries.stream().map(Entry::name).collect(Collectors.toUnmodifiableSet());
	}

	/** 축별 구획 어휘 블록 — 합성 프롬프트·배치 매핑 프롬프트 공용. */
	public String promptBlock() {
		Map<String, List<String>> byFacet = new LinkedHashMap<>();
		for (Entry e : entries) {
			byFacet.computeIfAbsent(e.facet(), k -> new ArrayList<>()).add(e.name());
		}
		return byFacet.entrySet().stream()
				.map(f -> "[" + f.getKey() + "] " + String.join(", ", f.getValue()))
				.collect(Collectors.joining("\n"));
	}
}
