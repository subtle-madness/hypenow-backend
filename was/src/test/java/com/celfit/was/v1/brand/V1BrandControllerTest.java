package com.celfit.was.v1.brand;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// V1InfluencerReportControllerTest와 같은 구성 — 이 컨트롤러는 Assembler·ClockConfig가 불필요해 제외.
@WebMvcTest(controllers = V1BrandController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1BrandControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1BrandRepository repository;

	@Test
	void 브랜드_협업_인플루언서_목록() throws Exception {
		given(repository.findInfluencers("브랜드A")).willReturn(List.of(
				new BrandInfluencer("minji.beauty", "민지", "/img/p.jpg", 85000L, 3L, "2026-07-20")));

		mockMvc.perform(get("/v1/brands/브랜드A/influencers").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].influencerId").value("minji.beauty"))
				.andExpect(jsonPath("$.data[0].collabCount").value(3));
	}

	@Test
	void 협업_없는_브랜드는_빈_배열() throws Exception {
		given(repository.findInfluencers("존재안함")).willReturn(List.of());

		mockMvc.perform(get("/v1/brands/존재안함/influencers").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isArray())
				.andExpect(jsonPath("$.data").isEmpty());
	}
}
