package com.celfit.analytics.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 분류 어휘의 단일 원천 — beauty_taxonomy·beauty_distributors 테이블(analysis DB, V30 시드)에서
 * {@link BeautyTaxonomyLoader}가 조립하는 불변 스냅샷. celfit-front 배포본 필터 어휘와 1:1이며
 * 분류값·라벨은 생산자(분석 층)가 확정하고 was는 verbatim 전달만 한다(ARCHITECTURE §4-4).
 * 프론트 mid/sub 필터는 sub_categories 배열 포함 여부로 매칭하므로
 * 중분류·소분류 라벨 표기가 한 글자라도 다르면 필터가 빈다 — 시드 수정 시 프론트와 함께 갱신할 것.
 * 프롬프트 분류표와 sanitize 어휘 집합이 같은 인스턴스에서 나온다 — 원천 분리 금지.
 */
public final class BeautyTaxonomy {

	/** 소분류당 1행. 목록 순서 = 시드 정렬 순서 (프롬프트 분류표 렌더링 순서). */
	public record Entry(String mainValue, String mainLabel, String midLabel, String subLabel) {
	}

	private final List<Entry> entries;
	private final List<String> distributors;
	private final Set<String> mainCategories;
	private final Set<String> distributorSet;
	private final Set<String> midAndSubLabels;
	private final Set<String> subLabels;

	public BeautyTaxonomy(List<Entry> entries, List<String> distributors) {
		this.entries = List.copyOf(entries);
		this.distributors = List.copyOf(distributors);
		this.mainCategories = entries.stream().map(Entry::mainValue)
				.collect(Collectors.toUnmodifiableSet());
		this.distributorSet = Set.copyOf(distributors);
		this.midAndSubLabels = entries.stream()
				.flatMap(e -> Stream.of(e.midLabel(), e.subLabel()))
				.collect(Collectors.toUnmodifiableSet());
		this.subLabels = entries.stream().map(Entry::subLabel)
				.collect(Collectors.toUnmodifiableSet());
	}

	/** 대분류 value(영문 slug) — content_analyses.main_category 어휘. */
	public Set<String> mainCategories() {
		return mainCategories;
	}

	/** 유통사 상호명 — 프론트 유통사 필터값. */
	public Set<String> distributors() {
		return distributorSet;
	}

	/** sub_categories 어휘 — 중분류+소분류 라벨 전체 (프론트가 배열 포함으로 매칭). */
	public Set<String> allMidAndSubLabels() {
		return midAndSubLabels;
	}

	/** detected_product_categories 어휘 — 소분류 라벨만 (카드 칩). */
	public Set<String> allSubLabels() {
		return subLabels;
	}

	/** 프롬프트에 넣는 유통사 나열 — 예: "올리브영|다이소". */
	public String distributorsPrompt() {
		return String.join("|", distributors);
	}

	/** 프롬프트에 넣는 분류표 — slug(한글 라벨): 중분류[소분류…] 계층, 행 순서 유지. */
	public String promptTable() {
		Map<String, String> mainLabels = new LinkedHashMap<>();
		Map<String, Map<String, List<String>>> tree = new LinkedHashMap<>();
		for (Entry e : entries) {
			mainLabels.putIfAbsent(e.mainValue(), e.mainLabel());
			tree.computeIfAbsent(e.mainValue(), k -> new LinkedHashMap<>())
					.computeIfAbsent(e.midLabel(), k -> new ArrayList<>())
					.add(e.subLabel());
		}
		return tree.entrySet().stream()
				.map(main -> "%s(%s): %s".formatted(main.getKey(), mainLabels.get(main.getKey()),
						main.getValue().entrySet().stream()
								.map(mid -> "%s[%s]".formatted(mid.getKey(), String.join(", ", mid.getValue())))
								.collect(Collectors.joining(" · "))))
				.collect(Collectors.joining("\n"));
	}
}
