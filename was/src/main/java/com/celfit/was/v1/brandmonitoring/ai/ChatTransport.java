package com.celfit.was.v1.brandmonitoring.ai;

import java.util.function.Consumer;

/**
 * 채팅 LLM 전송 seam(설계 §9) - 테스트에서 스크립트된 응답 fake로 갈아끼우는 지점이다.
 * 경로가 인자에 없는 이유: 이 표면이 쓰는 액션은 generateContent(streamGenerateContent 포함) 뿐이라
 * 경로 조립은 구현체 몫이다(monitoring GeminiHttp가 path를 받는 것과 의도적으로 다르다 - 거긴
 * AI Studio 경로 호환이 목적이었다).
 */
@FunctionalInterface
public interface ChatTransport {

	/** 요청 본문 JSON을 보내고 응답 본문 JSON을 그대로 돌려준다. 실패는 예외로 전파된다. */
	String post(String jsonBody);

	/**
	 * SSE 스트리밍 POST(T1, FE 변경요청서 §3.2) - data 페이로드가 도착할 때마다 {@code onData}로 넘긴다.
	 * 기본 구현은 미지원 예외를 던진다 - 스크립트 fake 등 완결 응답만 흉내내는 테스트 구현체는 이
	 * 메서드를 오버라이드하지 않아도 컴파일이 깨지지 않는다({@code @FunctionalInterface} 유지, default
	 * 메서드는 추상 메서드 수에 포함되지 않는다).
	 */
	default void postStream(String jsonBody, Consumer<String> onData) {
		throw new UnsupportedOperationException("이 전송은 스트리밍을 지원하지 않습니다.");
	}
}
