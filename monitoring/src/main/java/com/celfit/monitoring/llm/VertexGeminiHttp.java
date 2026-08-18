package com.celfit.monitoring.llm;

import com.celfit.common.llm.VertexHttpTransport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link GeminiHttp} seam의 Vertex 구현(2026-08-18 전환) — 호출부({@link BrandMentionJudge}·
 * {@link com.celfit.monitoring.ad.AdDisclosureExtractorGemini})는 AI Studio 경로
 * ("/v1beta/models/{model}:generateContent")를 그대로 넘기므로, 여기서 Vertex 경로
 * (projects/{p}/locations/{loc}/publishers/google/models/{m}:{action})로 변환한 뒤
 * common-llm {@link VertexHttpTransport}에 위임한다. 요청·응답 바디는 AI Studio와 동일
 * camelCase 구조라 변환이 필요 없다(analytics VertexHttpApiTest로 검증된 계약 — 08-18 재확인).
 */
public final class VertexGeminiHttp implements GeminiHttp {

	// "models/{model}:{action}" 세그먼트 추출 — action은 보통 generateContent 1가지뿐이라 캡처만 하고 그대로 재사용
	private static final Pattern MODEL_SEGMENT = Pattern.compile("models/([^/:]+):(.+)$");

	private final VertexHttpTransport transport;
	private final String project;
	private final String location;

	public VertexGeminiHttp(VertexHttpTransport transport, String project, String location) {
		this.transport = transport;
		this.project = project;
		this.location = location;
	}

	@Override
	public String post(String path, String jsonBody) {
		Matcher m = MODEL_SEGMENT.matcher(path);
		if (!m.find()) {
			throw new IllegalArgumentException("Vertex 경로 변환 실패 — 모델 세그먼트 없음: " + path);
		}
		String model = m.group(1);
		String action = m.group(2);
		String vertexPath = "/v1/projects/" + project + "/locations/" + location
				+ "/publishers/google/models/" + model + ":" + action;
		return transport.post(vertexPath, jsonBody);
	}
}
