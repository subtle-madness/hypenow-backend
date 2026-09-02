package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;

/**
 * 툴 1회 실행 결과(설계 §4·§8).
 *
 * <p>소유 검증 실패·잘못된 인자는 예외가 아니라 {@code failed} 결과다 - 모델이 자가 수정할 기회를
 * 줘야 하고(설계 §8), 무엇보다 LLM이 넘긴 값 하나 때문에 사용자 요청 전체를 500으로 만들 이유가 없다.
 *
 * @param payloadJson 모델에 되먹일 JSON. 실패면 {"error": "..."} 형태다.
 * @param rowCount    로그(app.ai_chat_logs.tool_calls[].rows)에 남길 결과 행 수.
 * @param shortCodes  이 호출이 언급한 게시물 shortCode - 응답의 참조 목록으로 모인다(설계 §5).
 */
public record AiToolResult(String payloadJson, int rowCount, List<String> shortCodes, boolean failed) {

	public static AiToolResult ok(String payloadJson, int rowCount, List<String> shortCodes) {
		return new AiToolResult(payloadJson, rowCount, List.copyOf(shortCodes), false);
	}

	/** 실패 메시지는 모델이 읽는다 - 무엇을 고쳐야 하는지 알 수 있게 쓴다. */
	public static AiToolResult failure(String payloadJson) {
		return new AiToolResult(payloadJson, 0, List.of(), true);
	}
}
