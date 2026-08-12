package com.celfit.was.v1.common;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// StubController는 이 테스트와 같은 패키지(com.celfit.was.v1.common)의 톱레벨 픽스처 클래스다 — static
// 중첩 클래스로 두면 컴포넌트 스캔 대상 빈으로 등록되지 않아(@WebMvcTest(controllers=...)를 써도 라우팅
// 자체가 안 잡힘, 실측 확인됨) 별도 파일로 분리했다. 패키지가 com.celfit.was.v1 하위라
// V1ExceptionAdvice의 basePackages = "com.celfit.was.v1" 매칭 대상이 된다.
@WebMvcTest(controllers = StubController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1ExceptionAdviceTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void V1ApiException은_스펙_envelope로_내려간다() throws Exception {
		mockMvc.perform(get("/v1/stub/not-found").with(user("tester")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("콘텐츠를 찾을 수 없습니다."));
	}

	@Test
	void 파라미터_형식_위반은_VALIDATION_FAILED_400() throws Exception {
		mockMvc.perform(get("/v1/stub/param").with(user("tester")).param("number", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 파라미터_누락도_VALIDATION_FAILED_400() throws Exception {
		mockMvc.perform(get("/v1/stub/param").with(user("tester")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 성공_envelope는_success_data_error_meta를_가진다() throws Exception {
		mockMvc.perform(get("/v1/stub/param").with(user("tester")).param("number", "7"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data").value("ok-7"))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.meta").doesNotExist());
	}

	@Test
	void meta가_있는_성공_응답은_meta를_직렬화한다() throws Exception {
		mockMvc.perform(get("/v1/stub/list").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data").value("rows"))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.meta.total").value(42));
	}

	// 405(메서드 불일치)는 핸들러 "선택 전" 매핑 단계에서 던져져 basePackages 한정 advice가 적용되지
	// 않는다(실측 — body가 비어 내려옴). 상태코드만 검증하고, envelope 검증은 핸들러 선택 후 던져지는
	// 415/본문 파싱 400 케이스로 대신한다. advice의 METHOD_NOT_ALLOWED 매핑은 핸들러 내부에서 해당
	// 예외가 발생하는 드문 경로를 위한 방어로 남겨둔다.
	@Test
	void 지원하지_않는_메서드는_405로_내려간다() throws Exception {
		mockMvc.perform(post("/v1/stub/param").with(csrf()).with(user("tester")))
				.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void 지원하지_않는_컨텐츠_타입은_UNSUPPORTED_MEDIA_TYPE_415() throws Exception {
		mockMvc.perform(post("/v1/stub/echo").with(csrf()).with(user("tester"))
						.contentType(MediaType.APPLICATION_XML).content("<x/>"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"))
				.andExpect(jsonPath("$.error.message").value("지원하지 않는 형식이에요."));
	}

	@Test
	void 본문_파싱_실패는_VALIDATION_FAILED_400() throws Exception {
		mockMvc.perform(post("/v1/stub/echo").with(csrf()).with(user("tester"))
						.contentType(MediaType.APPLICATION_JSON).content("{broken"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("요청 본문이 올바르지 않습니다."));
	}

	@Test
	void 예상밖_예외는_INTERNAL_ERROR_500이며_원본_메시지를_누출하지_않는다() throws Exception {
		mockMvc.perform(get("/v1/stub/boom").with(user("tester")))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.error.message").value("일시적인 오류가 발생했어요."));
	}

	// ── 클라 이탈(연결 끊김) 분기 ────────────────────────────────────────────
	// 유저가 탭을 닫거나 FE가 요청을 abort하면 응답 쓰기가 깨지는데, 이건 서버 결함이 아니고
	// 500 본문을 써봐야 받을 상대도 없다. 캐치올이 ERROR 로그 + 500 조립으로 처리하던 것을 끊는다.

	@Test
	void 클라_이탈은_500_본문을_쓰지_않는다() throws Exception {
		// 연결이 이미 죽었으니 아무것도 쓰지 않는 것이 정답 — 상태는 건드리지 않은 기본값 그대로다
		// (실제 운영에선 응답이 이미 200으로 커밋된 뒤라 바꿀 수도 없다).
		mockMvc.perform(get("/v1/stub/client-abort").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(content().string(""));
	}

	@Test
	void 상류_연결_끊김은_클라_이탈이_아니라_INTERNAL_ERROR_500이다() throws Exception {
		// 메시지는 클라 이탈과 똑같은 "Connection reset by peer"지만 끊긴 건 monitoring 쪽 연결이라
		// 우리 장애다. 여기가 500으로 남지 않으면 상류 장애가 통째로 조용해진다.
		mockMvc.perform(get("/v1/stub/upstream-abort").with(user("tester")))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
	}

	// ── MonitoringApiException → 프론트 어휘 변환(스펙 1.3) ─────────────────────
	// 분기는 monitoring code가 아니라 httpStatus 기준이라, 계약에 없는 임의 code로도 표를 고정한다.

	@Test
	void monitoring_400은_VALIDATION_FAILED_400이고_원문_메시지를_누출하지_않는다() throws Exception {
		mockMvc.perform(get("/v1/stub/monitoring-error").with(user("tester"))
						.param("code", "VALIDATION").param("status", "400"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("올바른 형식이 아니에요."))
				.andExpect(header().doesNotExist("Retry-After"));
	}

	@Test
	void monitoring_404은_NOT_FOUND_404이고_원문_메시지를_누출하지_않는다() throws Exception {
		mockMvc.perform(get("/v1/stub/monitoring-error").with(user("tester"))
						.param("code", "TARGET_NOT_FOUND").param("status", "404"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("대상을 찾을 수 없습니다."))
				.andExpect(header().doesNotExist("Retry-After"));
	}

	@Test
	void monitoring_409은_409대신_VALIDATION_FAILED_400이다() throws Exception {
		// 스펙 6.30: INVALID_STATE는 409가 아니라 400 VALIDATION_FAILED로 내려간다 — 프론트 어휘엔 409가 없다.
		mockMvc.perform(get("/v1/stub/monitoring-error").with(user("tester"))
						.param("code", "INVALID_STATE").param("status", "409"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("올바른 형식이 아니에요."));
	}

	@Test
	void monitoring_422도_VALIDATION_FAILED_400이다() throws Exception {
		// 프론트 어휘엔 422도 없다 — 404가 아닌 4xx는 전부 400으로 뭉갠다.
		mockMvc.perform(get("/v1/stub/monitoring-error").with(user("tester"))
						.param("code", "PRIVATE_ACCOUNT").param("status", "422"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("올바른 형식이 아니에요."));
	}

	@Test
	void monitoring_502는_SERVICE_UNAVAILABLE_503이고_Retry_After가_있다() throws Exception {
		mockMvc.perform(get("/v1/stub/monitoring-error").with(user("tester"))
						.param("code", "FETCH_FAILED").param("status", "502"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.error.message").value("일시적으로 연결이 어려워요. 잠시 후 다시 시도해 주세요."))
				.andExpect(header().string("Retry-After", "5"));
	}

	@Test
	void monitoring_500도_5xx라서_SERVICE_UNAVAILABLE_503이고_Retry_After가_있다() throws Exception {
		mockMvc.perform(get("/v1/stub/monitoring-error").with(user("tester"))
						.param("code", "INTERNAL").param("status", "500"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.error.message").value("일시적으로 연결이 어려워요. 잠시 후 다시 시도해 주세요."))
				.andExpect(header().string("Retry-After", "5"));
	}
}
