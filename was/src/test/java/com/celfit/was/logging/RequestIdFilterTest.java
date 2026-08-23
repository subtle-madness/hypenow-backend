package com.celfit.was.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 요청 ID MDC 필터(was) — monitoring 쪽 RequestIdFilter와 같은 계약: 체인 동안 MDC에 requestId,
 * 유효한 X-Request-Id는 이어받기, 응답 후 정리(스레드 재사용 누수 방지).
 */
class RequestIdFilterTest {

	private final RequestIdFilter filter = new RequestIdFilter();

	@Test
	void 헤더가_없으면_ID를_생성해_체인_동안_MDC에_넣는다() throws ServletException, IOException {
		AtomicReference<String> seen = new AtomicReference<>();
		FilterChain chain = (req, res) -> seen.set(MDC.get("requestId"));

		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

		assertThat(seen.get()).isNotNull().hasSize(8).matches("[a-z0-9]{8}");
	}

	@Test
	void 유효한_X_Request_Id_헤더는_그대로_이어받는다() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Request-Id", "abc123XY");
		AtomicReference<String> seen = new AtomicReference<>();
		FilterChain chain = (req, res) -> seen.set(MDC.get("requestId"));

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(seen.get()).isEqualTo("abc123XY");
	}

	@Test
	void 무효_헤더는_이어받지_않고_새로_생성한다() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Request-Id", "개행\n주입");
		AtomicReference<String> seen = new AtomicReference<>();
		FilterChain chain = (req, res) -> seen.set(MDC.get("requestId"));

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(seen.get()).isNotNull().matches("[a-z0-9]{8}");
	}

	@Test
	void 응답_후_MDC를_정리한다() throws ServletException, IOException {
		FilterChain chain = (req, res) -> {
		};

		filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

		assertThat(MDC.get("requestId")).isNull();
	}

	@Test
	void 응답_헤더에도_ID를_실어_호출자가_로그를_찾을_수_있게_한다() throws ServletException, IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seen = new AtomicReference<>();
		FilterChain chain = (req, res) -> seen.set(MDC.get("requestId"));

		filter.doFilter(new MockHttpServletRequest(), response, chain);

		assertThat(response.getHeader("X-Request-Id")).isEqualTo(seen.get());
	}
}
