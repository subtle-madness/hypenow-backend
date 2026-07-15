package com.celfit.was.v1.common;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
	void 성공_envelope는_success_data_error_meta를_가진다() throws Exception {
		mockMvc.perform(get("/v1/stub/param").param("number", "7"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data").value("ok-7"))
				.andExpect(jsonPath("$.error").value(nullValue()));
	}
}
