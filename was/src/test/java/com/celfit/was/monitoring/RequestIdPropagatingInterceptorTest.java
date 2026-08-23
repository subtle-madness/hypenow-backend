package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * was→monitoring 내부 호출의 요청 ID 전파 — MDC의 requestId를 X-Request-Id 헤더로 실어
 * monitoring RequestIdFilter가 이어받게 한다(두 모듈 로그가 같은 ID로 묶이는 연결 고리).
 */
class RequestIdPropagatingInterceptorTest {

	private final RequestIdPropagatingInterceptor interceptor = new RequestIdPropagatingInterceptor();

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void MDC의_requestId를_X_Request_Id_헤더로_싣는다() throws IOException {
		MDC.put("requestId", "abc12345");
		MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://monitoring:8083/api/brands"));

		interceptor.intercept(request, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], 200));

		assertThat(request.getHeaders().getFirst("X-Request-Id")).isEqualTo("abc12345");
	}

	@Test
	void MDC에_requestId가_없으면_헤더를_싣지_않는다() throws IOException {
		MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://monitoring:8083/api/brands"));

		interceptor.intercept(request, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], 200));

		assertThat(request.getHeaders().getFirst("X-Request-Id")).isNull();
	}
}
