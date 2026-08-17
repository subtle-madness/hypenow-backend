package com.celfit.monitoring.ad;

import com.celfit.monitoring.ad.AdDisclosureExtractor.Category;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Tier3 — 환각 차단 + 위치 판정 + 최종 조합(스펙 §5). LLM·DB 무관, 전부 결정적 순수 함수라
 * 골드셋 없이도 지침 원문 예시 전수 테스트가 가능하다(스펙 §10-1).
 *
 * <p>우선순위(조합표 순서 그대로): CLEAR+적절위치 → DISCLOSED. CLEAR뿐이나 전부 묻힘 →
 * INSUFFICIENT+HIDDEN_PLACEMENT. AMBIGUOUS만 → INSUFFICIENT+AMBIGUOUS_EXPRESSION(묻힘 병기).
 * FOREIGN만 → INSUFFICIENT+FOREIGN_LANGUAGE. UNCERTAIN뿐 → UNCERTAIN. 유효 문구 전무 →
 * 사진 NOT_DISCLOSED+NO_DISCLOSURE / 릴스 UNCERTAIN(Tier0과 같은 분기 — 문구가 전부 환각 폐기된
 * 경우도 여기로 떨어진다).
 *
 * <p>카테고리는 내부적으로 {@link Category} enum으로 다룬다 — DB·was 응답의 {@code
 * AdVerdictResult.Evidence.category}만 계약대로 문자열이라 evidence 생성 시점에 {@link
 * Category#name()}으로 변환한다.
 */
public final class AdVerdictCombiner {

	private AdVerdictCombiner() {
	}

	private record Evaluated(String phrase, Category category, AdPositionRule.Band band, int graphemeOffset,
			String source) {
	}

	/**
	 * @param tier1Match Tier1이 찾았지만(위치 부적절 등으로) 확정 못 하고 넘어온 매칭 — null 허용.
	 *                   Tier3는 이 매칭도 CLEAR/RULE 후보로 재평가한다(위치가 그새 바뀌지 않으므로
	 *                   보통 같은 band가 나오지만, 판정 로직을 한곳(AdPositionRule)에만 둔다).
	 * @param llmDisclosures Tier2 추출 결과 — 캡션에 실존하지 않는 phrase(환각)나 공백 phrase는
	 *                       여기서 폐기돼 결과의 {@code discardedPhrases}로만 남는다.
	 */
	public static AdVerdictResult combine(String caption, boolean isReels, AdDisclosurePatterns.Match tier1Match,
			List<AdDisclosureExtractor.Disclosure> llmDisclosures) {
		List<Evaluated> candidates = new ArrayList<>();
		List<String> discarded = new ArrayList<>();
		if (tier1Match != null) {
			candidates.add(evaluate(caption, tier1Match.phrase(), tier1Match.start(), tier1Match.end(),
					Category.CLEAR, "RULE"));
		}
		for (AdDisclosureExtractor.Disclosure d : llmDisclosures) {
			if (d.phrase() == null || d.phrase().isBlank()) {
				discarded.add(d.phrase() == null ? "" : d.phrase());   // 공백 phrase 가드
				continue;
			}
			int idx = caption.indexOf(d.phrase());
			if (idx < 0) {
				discarded.add(d.phrase());   // 환각 차단(스펙 §5 Tier3) — 캡션에 실존하지 않는 문구는 판정에서 배제
				continue;
			}
			candidates.add(evaluate(caption, d.phrase(), idx, idx + d.phrase().length(),
					d.category(), "LLM"));
		}
		return decide(dedupe(candidates), isReels, discarded);
	}

	private static Evaluated evaluate(String caption, String phrase, int start, int end, Category category,
			String source) {
		AdPositionRule.Band band = AdPositionRule.evaluate(caption, start, end);
		int offset = AdPositionRule.graphemeOffset(caption, start);
		return new Evaluated(phrase, category, band, offset, source);
	}

	/** 같은 (phrase, 그래핌 오프셋)이 Tier1·Tier2 양쪽에서 나오면 evidence 중복을 접는다. */
	private static List<Evaluated> dedupe(List<Evaluated> in) {
		LinkedHashMap<String, Evaluated> byKey = new LinkedHashMap<>();
		for (Evaluated e : in) {
			byKey.putIfAbsent(e.phrase() + "|" + e.graphemeOffset(), e);
		}
		return List.copyOf(byKey.values());
	}

	private static AdVerdictResult decide(List<Evaluated> candidates, boolean isReels, List<String> discardedSoFar) {
		List<AdVerdictResult.Evidence> evidence = candidates.stream()
				.map(c -> new AdVerdictResult.Evidence(c.phrase(), c.category().name(), c.graphemeOffset()))
				.toList();

		List<Evaluated> clear = byCategory(candidates, Category.CLEAR);
		List<Evaluated> ambiguous = byCategory(candidates, Category.AMBIGUOUS);
		List<Evaluated> foreign = byCategory(candidates, Category.FOREIGN);
		List<Evaluated> uncertain = byCategory(candidates, Category.UNCERTAIN);
		List<String> discardedPhrases = withUnclassified(discardedSoFar, candidates, clear, ambiguous, foreign,
				uncertain);

		Optional<Evaluated> clearAccepted = clear.stream().filter(AdVerdictCombiner::accepted).findFirst();
		if (clearAccepted.isPresent()) {
			return new AdVerdictResult("DISCLOSED", clearAccepted.get().source(), List.of(), evidence,
					discardedPhrases);
		}
		if (!clear.isEmpty()) {
			return new AdVerdictResult("INSUFFICIENT", clear.get(0).source(), List.of("HIDDEN_PLACEMENT"), evidence,
					discardedPhrases);
		}

		if (!ambiguous.isEmpty()) {
			List<String> violations = new ArrayList<>();
			violations.add("AMBIGUOUS_EXPRESSION");
			if (ambiguous.stream().anyMatch(c -> c.band() == AdPositionRule.Band.HIDDEN)) {
				violations.add("HIDDEN_PLACEMENT");
			}
			return new AdVerdictResult("INSUFFICIENT", "LLM", violations, evidence, discardedPhrases);
		}

		if (!foreign.isEmpty()) {
			return new AdVerdictResult("INSUFFICIENT", "LLM", List.of("FOREIGN_LANGUAGE"), evidence,
					discardedPhrases);
		}

		if (!uncertain.isEmpty()) {
			return new AdVerdictResult("UNCERTAIN", "LLM", List.of(), evidence, discardedPhrases);
		}

		// 유효 문구 전무(전부 환각으로 폐기된 경우 포함) — Tier0과 같은 매체별 분기.
		return isReels
				? new AdVerdictResult("UNCERTAIN", "RULE", List.of(), evidence, discardedPhrases)
				: new AdVerdictResult("NOT_DISCLOSED", "RULE", List.of("NO_DISCLOSURE"), evidence, discardedPhrases);
	}

	/**
	 * 조합표가 다루는 4개 카테고리(CLEAR/AMBIGUOUS/FOREIGN/UNCERTAIN) 어디에도 속하지 않는 후보를
	 * discardedPhrases에 병합한다. {@link Category}는 오늘 이 4개뿐이라 항상 비어 있지만, 향후 enum이
	 * 확장되면 조용히 verdict에서 누락되는 대신 여기로 걸려 호출자가 로그할 수 있다.
	 */
	private static List<String> withUnclassified(List<String> discardedSoFar, List<Evaluated> candidates,
			List<Evaluated> clear, List<Evaluated> ambiguous, List<Evaluated> foreign, List<Evaluated> uncertain) {
		List<String> unclassified = candidates.stream()
				.filter(c -> !clear.contains(c) && !ambiguous.contains(c) && !foreign.contains(c)
						&& !uncertain.contains(c))
				.map(Evaluated::phrase)
				.toList();
		if (unclassified.isEmpty()) {
			return List.copyOf(discardedSoFar);
		}
		List<String> merged = new ArrayList<>(discardedSoFar);
		merged.addAll(unclassified);
		return List.copyOf(merged);
	}

	private static boolean accepted(Evaluated c) {
		return c.band() == AdPositionRule.Band.VISIBLE || c.band() == AdPositionRule.Band.GRAY
				|| c.band() == AdPositionRule.Band.FIRST_HASHTAG;
	}

	private static List<Evaluated> byCategory(List<Evaluated> in, Category category) {
		return in.stream().filter(c -> c.category() == category).toList();
	}
}
