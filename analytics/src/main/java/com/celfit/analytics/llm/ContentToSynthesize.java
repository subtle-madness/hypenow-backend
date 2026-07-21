package com.celfit.analytics.llm;

import java.util.Map;

/**
 * 해석 문구만 다시 만들 때의 입력 — 이미 저장된 <b>사실</b>(파트 A 산출물)에 <b>새 기준선</b>을 붙인다.
 *
 * <p>통합 분석({@link ContentToAnalyze})과 달리 캡션·썸네일·분류표가 필요 없다.
 * 사실은 캡션에서 이미 뽑아 저장해 뒀고, 낡은 건 기준선을 인용한 문구뿐이기 때문이다 —
 * 그래서 프롬프트가 짧고 이미지 전송이 없어 통합 재분석보다 훨씬 싸다.
 *
 * @param facts content_analyses에 저장된 파트 A 산출물 (브랜드·카테고리·ad_type·제품 등).
 *        LLM이 다시 추출하지 않고 <b>주어진 사실로</b> 받아 해석에만 쓴다.
 */
public record ContentToSynthesize(String shortCode, String accountHandle, String contentType,
		Long views, Long likes, Long comments, Map<String, Object> baseline,
		Map<String, Long> commentCategoryCounts, Map<String, Object> facts) {
}
