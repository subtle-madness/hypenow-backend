package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;

/**
 * 질의 요청(FE 변경요청서 2026-08-28 §3·§5) - {@code POST /v1/brand-monitoring/ai/messages}. 무상태
 * 이력 통째 전송이던 옛 계약({@code AiChatRequest})을 대체한다 - 이력은 서버가 conversationId로
 * 복원한다(설계 §8, {@link V1BrandAiMessagesController}).
 *
 * @param conversationId null이면 새 대화를 만든다.
 * @param accountIds     정확히 1개여야 한다(컨트롤러가 검증) - 다중 계정 비교는 이번 계약 밖.
 * @param presetId       프리셋 질문 식별자. 미등록 값이어도 오류가 아니라 자유 질의로 폴백한다.
 * @param text           사용자 질문 원문(1~2000자, 컨트롤러가 검증).
 * @param scope          FE 화면 필터. null이면 무필터(전체 조회).
 */
public record AiMessagesRequest(String conversationId, List<String> accountIds, String presetId, String text,
		ScopeRequest scope) {

	/**
	 * scope 원문 표현(FE §5) - 날짜는 문자열로 받아 {@link AiScope#from}에서 검증·파싱한다(Jackson
	 * LocalDate 자동 변환에 기대지 않고 파싱 실패를 400으로 명시적으로 잡기 위해서다).
	 */
	public record ScopeRequest(String dateFrom, String dateTo, String mediaType, String sponsorship, String source,
			Integer followerMin, Integer followerMax, String q) {
	}
}
