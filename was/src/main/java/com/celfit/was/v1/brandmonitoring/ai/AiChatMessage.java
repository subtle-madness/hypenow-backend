package com.celfit.was.v1.brandmonitoring.ai;

/**
 * 무상태 대화 이력 1건(설계 §5) - 서버 세션이 없으므로 프론트가 매 요청에 전체를 실어 보낸다.
 *
 * @param role "user" 또는 "assistant". 그 외 값은 컨트롤러가 거른다.
 */
public record AiChatMessage(String role, String content) {

	public static final String ROLE_USER = "user";
	public static final String ROLE_ASSISTANT = "assistant";
}
