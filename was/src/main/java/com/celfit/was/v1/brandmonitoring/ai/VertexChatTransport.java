package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.common.llm.VertexHttpTransport;
import java.util.function.Consumer;

/**
 * {@link ChatTransport}의 Vertex 구현 - generateContent 경로를 조립해 common-llm 전송에 위임한다
 * (monitoring {@code VertexGeminiHttp}와 같은 역할이되, 이쪽은 AI Studio 경로 변환이 필요 없어
 * 처음부터 Vertex 경로를 만든다).
 *
 * <p>common-llm은 프롬프트·툴 정의를 모르는 순수 전송 계층이고, function calling 페이로드는
 * 그냥 JSON 본문의 일부라 이 경로에 common-llm 확장이 필요 없다(설계 §3 확인 완료, 08-27).
 *
 * <p>스트리밍 경로(T1)는 별도 경로 {@code :streamGenerateContent?alt=sse}를 쓴다(Vertex REST 계약) -
 * generateContent와 같은 요청 본문을 그대로 재사용하되 경로만 다르다.
 */
public final class VertexChatTransport implements ChatTransport {

	private final VertexHttpTransport transport;
	private final String path;
	private final String streamPath;

	public VertexChatTransport(VertexHttpTransport transport, String project, String location, String model) {
		this.transport = transport;
		String base = "/v1/projects/" + project + "/locations/" + location + "/publishers/google/models/" + model;
		this.path = base + ":generateContent";
		this.streamPath = base + ":streamGenerateContent?alt=sse";
	}

	@Override
	public String post(String jsonBody) {
		return transport.post(path, jsonBody);
	}

	@Override
	public void postStream(String jsonBody, Consumer<String> onData) {
		transport.postStream(streamPath, jsonBody, onData);
	}
}
