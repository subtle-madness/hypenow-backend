package com.celfit.monitoring.ad;

import java.util.List;

/**
 * Tier0~3 최종 판정 — verdict 5종(DISCLOSED/NOT_DISCLOSED/INSUFFICIENT/UNCERTAIN/FOREIGN_POST) ·
 * source(RULE/LLM) · violations 코드 배열 · evidence 근거 문구(스펙 §4 컬럼과 1:1).
 * FOREIGN_POST(2026-08-27 추가, DECISIONS.md 참조)는 캡션 전체가 비한국어라 한국 공정위 지침
 * 적용 대상이 아닌 게시물을 판정 제외한다는 뜻이다 — violations·evidence는 항상 빈 리스트.
 *
 * @param discardedPhrases 판정 계산에 반영되지 못하고 버려진 phrase 원문 — (1) LLM이 인용했지만
 *                          캡션에 실존하지 않거나(환각 차단) 공백인 phrase, (2) {@link
 *                          AdDisclosureExtractor.Category}가 향후 확장돼 조합표(decide())가 아직
 *                          처리하지 못하는 카테고리로 분류된 phrase를 모두 포함한다. <b>DB·API
 *                          계약에는 포함하지 않는다</b> — 호출자(판정 오케스트레이터)가 로그만
 *                          남기기 위한 부가정보다.
 */
public record AdVerdictResult(String verdict, String source, List<String> violations, List<Evidence> evidence,
		List<String> discardedPhrases) {

	public record Evidence(String phrase, String category, int offset) {
	}
}
