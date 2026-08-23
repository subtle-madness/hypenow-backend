package com.celfit.monitoring.web;

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
 * 요청 ID MDC 필터 — 로그를 요청 단위로 묶는 유일한 장치라, 계약은 세 가지다:
 * (1) 체인 실행 중 MDC에 requestId가 있어야 하고 (2) was가 넘긴 X-Request-Id는 그대로 이어받아
 * 두 모듈 로그가 같은 ID로 묶여야 하며 (3) 응답 후 MDC가 정리돼 스레드 재사용 시 남은 ID가
 * 다른 요청 로그에 새지 않아야 한다.
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
		request.addHeader("X-Request-Id", "공백 있고 한글이라 무효");
		AtomicReference<String> seen = new AtomicReference<>();
		FilterChain chain = (req, res) -> seen.set(MDC.get("requestId"));

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		assertThat(seen.get()).isNotNull().matches("[a-z0-9]{8}");
	}

	@Test
	void 너무_긴_헤더는_이어받지_않는다() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Request-Id", "a".repeat(65));
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
	void 체인이_예외를_던져도_MDC를_정리한다() {
		FilterChain chain = (req, res) -> {
			throw new IOException("다운스트림 실패");
		};

		try {
			filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
		} catch (ServletException | IOException expected) {
			// 예외 자체는 관심사 아님 — 정리만 본다
		}

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
