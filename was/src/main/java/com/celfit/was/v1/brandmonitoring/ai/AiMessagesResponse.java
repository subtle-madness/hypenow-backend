package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;

/**
 * 질의 응답(FE 변경요청서 2026-08-28 §3·§6·§7) - 완결 JSON(SSE는 이번 범위 밖). conversationId·
 * messageId는 문자열 직렬화(FE 계약 관용구 - accountId·conversationId 등 식별자는 전부 문자열).
 *
 * @param conversationId 이 질문이 속한 대화 id(신규 대화면 이번에 만들어진 id).
 * @param messageId      이 질문+답변을 적재한 로그 행 id. 로그 적재 실패(fire-and-forget) 시 null.
 * @param content        답변 마크다운 본문(허용 문법은 {@link BrandAiPrompt} 참조).
 * @param followUps      후속 질문 제안(정확히 2개 - deepen 1·action 1). 생성 실패·타임아웃 시 빈 배열.
 * @param references     답변이 인용한 참조 목록(최대 10개).
 * @param limitReached   예산 도달로 부분 답변이 된 경우 그 원인("time"=시간, "budget"=조회 예산),
 *                       정상 완료면 null(스펙 §5 구조 고지 - FE 협의 전이라 additive로만 싣는다).
 */
public record AiMessagesResponse(String conversationId, String messageId, String content,
		List<FollowUp> followUps, List<Reference> references, String limitReached) {

	/** @param kind "deepen"(구체 대상 심화) 또는 "action"(다음 행동으로 이어지는 질문). */
	public record FollowUp(String text, String kind) {
	}

	/** @param type 현재는 "post"만(influencer 타입은 범위 밖, §7). brandPostId는 shortCode 그대로다. */
	public record Reference(String type, String brandPostId, String label) {

		public static final String TYPE_POST = "post";
	}
}
