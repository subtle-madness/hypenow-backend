package com.celfit.monitoring.ad;

import java.util.List;

/**
 * Tier2 LLM 문구 추출 seam — 구현체는 {@link AdDisclosureExtractorGemini}. 이 인터페이스는
 * {@link AdVerdictCombiner}가 의존하는 출력 타입(Disclosure·Category)을 확정해 Tier3를 LLM 없이
 * 테스트할 수 있게 한다.
 */
public interface AdDisclosureExtractor {

	enum Category { CLEAR, AMBIGUOUS, FOREIGN, UNCERTAIN }

	record Disclosure(String phrase, Category category) {
	}

	List<Disclosure> extract(String caption);
}
