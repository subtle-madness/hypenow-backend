package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 익명 세션 누적 회귀 방지, 404 → ERROR 포워드 경로(08-02 실측 SAVED_REQUEST url 1위 "/error" 377건).
 * MockMvc(mock 서블릿)는 컨테이너 네이티브 error-page 포워드를 재현하지 못해(AnonymousSessionLeakTest의
 * /v1/me 케이스와 달리) 실 톰캣(RANDOM_PORT)으로 검증한다 — ProdForwardedHeadersTest와 같은 이유.
 *
 * <p>permitAll 프리픽스(/v1/auth/**) 아래 매핑되지 않은 경로를 치면: Security는 permitAll이라 통과시키고
 * DispatcherServlet이 404를 던지면 톰캣이 등록된 error-page로 "/error"를 내부 포워드한다. 그 포워드된
 * 요청은 dispatcherType=ERROR로 SecurityFilterChain을 다시 타는데, "/error"는 permitAll 목록에 없어
 * anyRequest().authenticated()에 걸린다 — 이때 기본 HttpSessionRequestCache가 세션을 새로 만들었다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AnonymousSessionLeakErrorForwardTest extends IntegrationTest {

	@LocalServerPort
	int port;

	@Test
	void permitAll_경로_아래_존재하지_않는_핸들러는_error_포워드에서도_세션을_만들지_않는다() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(
						URI.create("http://127.0.0.1:" + port + "/v1/auth/does-not-exist"))
				.GET()
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		List<String> setCookies = response.headers().allValues("Set-Cookie");
		assertThat(setCookies).noneMatch(header -> header.startsWith("hypenow-session="));
	}
}
