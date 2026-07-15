package com.celfit.was.v1.common;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
		mockMvc.perform(get("/v1/stub/not-found"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("콘텐츠를 찾을 수 없습니다."));
	}

	@Test
	void 파라미터_형식_위반은_VALIDATION_FAILED_400() throws Exception {
		mockMvc.perform(get("/v1/stub/param").param("number", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 파라미터_누락도_VALIDATION_FAILED_400() throws Exception {
		mockMvc.perform(get("/v1/stub/param"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 성공_envelope는_success_data_error_meta를_가진다() throws Exception {
		mockMvc.perform(get("/v1/stub/param").param("number", "7"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data").value("ok-7"))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.meta").doesNotExist());
	}

	@Test
	void meta가_있는_성공_응답은_meta를_직렬화한다() throws Exception {
		mockMvc.perform(get("/v1/stub/list"))
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
		mockMvc.perform(post("/v1/stub/param").with(csrf()))
				.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void 지원하지_않는_컨텐츠_타입은_UNSUPPORTED_MEDIA_TYPE_415() throws Exception {
		mockMvc.perform(post("/v1/stub/echo").with(csrf())
						.contentType(MediaType.APPLICATION_XML).content("<x/>"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"))
				.andExpect(jsonPath("$.error.message").value("지원하지 않는 형식이에요."));
	}

	@Test
	void 본문_파싱_실패는_VALIDATION_FAILED_400() throws Exception {
		mockMvc.perform(post("/v1/stub/echo").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{broken"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("요청 본문이 올바르지 않습니다."));
	}

	@Test
	void 예상밖_예외는_INTERNAL_ERROR_500이며_원본_메시지를_누출하지_않는다() throws Exception {
		mockMvc.perform(get("/v1/stub/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.error.message").value("일시적인 오류가 발생했어요."));
	}
}
