package com.celfit.analytics.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
	private final Map<String, String> labelToMain; // mid·sub 라벨 → 단일 대분류(애매하면 제외)
	private final List<String> mainOrder;          // 대분류 최초 등장 순서(분류표 순 tie-break)

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

		Map<String, Set<String>> byLabel = new LinkedHashMap<>();
		List<String> order = new ArrayList<>();
		for (Entry e : entries) {
			if (!order.contains(e.mainValue())) {
				order.add(e.mainValue());
			}
			byLabel.computeIfAbsent(e.midLabel(), k -> new HashSet<>()).add(e.mainValue());
			byLabel.computeIfAbsent(e.subLabel(), k -> new HashSet<>()).add(e.mainValue());
		}
		Map<String, String> single = new HashMap<>();
		byLabel.forEach((label, mains) -> {
			if (mains.size() == 1) {
				single.put(label, mains.iterator().next());
			}
		});
		this.labelToMain = Map.copyOf(single);
		this.mainOrder = List.copyOf(order);
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

	/**
	 * 유효 라벨(중·소분류)들이 가리키는 대분류를 최다 득표로 역유도한다 (sanitize 복구용).
	 * 여러 대분류에 걸치는 애매한 라벨은 집계에서 제외, 동점은 분류표 앞선 대분류로 결정론적 tie-break.
	 * @return 역유도된 대분류 slug, 매칭 라벨이 없으면 null.
	 */
	public String deriveMain(List<String> labels) {
		if (labels == null) {
			return null;
		}
		Map<String, Long> votes = new LinkedHashMap<>();
		for (String label : labels) {
			if (label == null) {
				continue;
			}
			String main = labelToMain.get(label);
			if (main != null) {
				votes.merge(main, 1L, Long::sum);
			}
		}
		if (votes.isEmpty()) {
			return null;
		}
		long max = votes.values().stream().max(Long::compare).orElseThrow();
		return votes.entrySet().stream()
				.filter(e -> e.getValue() == max)
				.map(Map.Entry::getKey)
				.min(java.util.Comparator.comparingInt(mainOrder::indexOf))
				.orElseThrow();
	}
}
