package com.celfit.was.v1.brandmonitoring.ai;

/**
 * 채팅 LLM 전송 seam(설계 §9) - 테스트에서 스크립트된 응답 fake로 갈아끼우는 지점이다.
 * 경로가 인자에 없는 이유: 이 표면이 쓰는 액션은 generateContent 하나뿐이라 경로 조립은 구현체 몫이다
 * (monitoring GeminiHttp가 path를 받는 것과 의도적으로 다르다 - 거긴 AI Studio 경로 호환이 목적이었다).
 */
@FunctionalInterface
public interface ChatTransport {

	/** 요청 본문 JSON을 보내고 응답 본문 JSON을 그대로 돌려준다. 실패는 예외로 전파된다. */
	String post(String jsonBody);
}
