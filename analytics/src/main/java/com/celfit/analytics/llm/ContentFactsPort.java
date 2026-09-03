package com.celfit.analytics.llm;

/**
 * 파트 A(사실 추출) 전용 포트 - 캡션과 인스타 유료 파트너십 태그만 입력받아
 * {@link ContentAttributes}를 낸다(2026-09-03 2단계 분리 설계 §4-4).
 *
 * <p>{@link ContentAttributePort}(캡션·썸네일 2인자)로는 유료 파트너십 태그를 실을 수 없어
 * 별도로 둔다 - 태그를 안 실으면 태그가 붙은 게시물을 LLM이 organic으로 뒤집는다(운영 실측 87건).
 *
 * <p>온라인 폴백 경로 전용이다. 배치 경로는 {@code GeminiBatchLines.factsRequestLine}이
 * 같은 프롬프트·스키마를 JSONL로 조립한다.
 */
public interface ContentFactsPort {

	/** @param thumbnailUrl null이면 캡션만으로 추출한다(배치 경로는 항상 null). */
	ContentAttributes extractFacts(ContentToAnalyze content, String thumbnailUrl);
}
