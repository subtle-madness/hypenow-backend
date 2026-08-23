package com.celfit.was.monitoring;

import com.celfit.was.logging.RequestIdFilter;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * was→monitoring 내부 호출에 현재 요청의 MDC requestId를 X-Request-Id 헤더로 싣는다 —
 * monitoring의 RequestIdFilter가 이어받아 두 모듈 로그가 같은 ID로 묶인다. MDC가 비어 있으면
 * (스케줄 잡 등 요청 밖 호출) 헤더를 싣지 않고 monitoring이 자체 생성한다.
 */
public class RequestIdPropagatingInterceptor implements ClientHttpRequestInterceptor {

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		String requestId = MDC.get(RequestIdFilter.MDC_KEY);
		if (requestId != null) {
			request.getHeaders().set(RequestIdFilter.HEADER, requestId);
		}
		return execution.execute(request, body);
	}
}
