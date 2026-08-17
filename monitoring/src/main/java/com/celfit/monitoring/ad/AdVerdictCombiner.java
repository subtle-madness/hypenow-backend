package com.celfit.monitoring.ad;

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
 */
public final class AdVerdictCombiner {

	private AdVerdictCombiner() {
	}

	private record Evaluated(String phrase, String category, AdPositionRule.Band band, int graphemeOffset,
			String source) {
	}

	/**
	 * @param tier1Match Tier1이 찾았지만(위치 부적절 등으로) 확정 못 하고 넘어온 매칭 — null 허용.
	 *                   Tier3는 이 매칭도 CLEAR/RULE 후보로 재평가한다(위치가 그새 바뀌지 않으므로
	 *                   보통 같은 band가 나오지만, 판정 로직을 한곳(AdPositionRule)에만 둔다).
	 * @param llmDisclosures Tier2 추출 결과 — 캡션에 실존하지 않는 phrase는 여기서 폐기된다(환각 차단).
	 */
	public static AdVerdictResult combine(String caption, boolean isReels, AdDisclosurePatterns.Match tier1Match,
			List<AdDisclosureExtractor.Disclosure> llmDisclosures) {
		List<Evaluated> candidates = new ArrayList<>();
		if (tier1Match != null) {
			candidates.add(evaluate(caption, tier1Match.phrase(), tier1Match.start(), tier1Match.end(),
					"CLEAR", "RULE"));
		}
		for (AdDisclosureExtractor.Disclosure d : llmDisclosures) {
			int idx = caption.indexOf(d.phrase());
			if (idx < 0) {
				continue;   // 환각 차단(스펙 §5 Tier3) — 캡션에 실존하지 않는 문구는 판정에서 배제
			}
			candidates.add(evaluate(caption, d.phrase(), idx, idx + d.phrase().length(),
					d.category().name(), "LLM"));
		}
		return decide(dedupe(candidates), isReels);
	}

	private static Evaluated evaluate(String caption, String phrase, int start, int end, String category,
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

	private static AdVerdictResult decide(List<Evaluated> candidates, boolean isReels) {
		List<AdVerdictResult.Evidence> evidence = candidates.stream()
				.map(c -> new AdVerdictResult.Evidence(c.phrase(), c.category(), c.graphemeOffset()))
				.toList();

		List<Evaluated> clear = byCategory(candidates, "CLEAR");
		Optional<Evaluated> clearAccepted = clear.stream().filter(AdVerdictCombiner::accepted).findFirst();
		if (clearAccepted.isPresent()) {
			return new AdVerdictResult("DISCLOSED", clearAccepted.get().source(), List.of(), evidence);
		}
		if (!clear.isEmpty()) {
			return new AdVerdictResult("INSUFFICIENT", clear.get(0).source(), List.of("HIDDEN_PLACEMENT"), evidence);
		}

		List<Evaluated> ambiguous = byCategory(candidates, "AMBIGUOUS");
		if (!ambiguous.isEmpty()) {
			List<String> violations = new ArrayList<>();
			violations.add("AMBIGUOUS_EXPRESSION");
			if (ambiguous.stream().anyMatch(c -> c.band() == AdPositionRule.Band.HIDDEN)) {
				violations.add("HIDDEN_PLACEMENT");
			}
			return new AdVerdictResult("INSUFFICIENT", "LLM", violations, evidence);
		}

		List<Evaluated> foreign = byCategory(candidates, "FOREIGN");
		if (!foreign.isEmpty()) {
			return new AdVerdictResult("INSUFFICIENT", "LLM", List.of("FOREIGN_LANGUAGE"), evidence);
		}

		boolean anyUncertain = candidates.stream().anyMatch(c -> "UNCERTAIN".equals(c.category()));
		if (anyUncertain) {
			return new AdVerdictResult("UNCERTAIN", "LLM", List.of(), evidence);
		}

		// 유효 문구 전무(전부 환각으로 폐기된 경우 포함) — Tier0과 같은 매체별 분기.
		return isReels
				? new AdVerdictResult("UNCERTAIN", "RULE", List.of(), evidence)
				: new AdVerdictResult("NOT_DISCLOSED", "RULE", List.of("NO_DISCLOSURE"), evidence);
	}

	private static boolean accepted(Evaluated c) {
		return c.band() == AdPositionRule.Band.VISIBLE || c.band() == AdPositionRule.Band.GRAY
				|| c.band() == AdPositionRule.Band.FIRST_HASHTAG;
	}

	private static List<Evaluated> byCategory(List<Evaluated> in, String category) {
		return in.stream().filter(c -> category.equals(c.category())).toList();
	}
}
