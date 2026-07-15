package com.celfit.was.v1.influencer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.content.ContentCardAssembler;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// V1ContentControllerTest와 같은 구성(WebConfig는 SecurityConfig로 일원화됨).
@WebMvcTest(controllers = V1InfluencerController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({ContentCardAssembler.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1InfluencerControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1InfluencerRepository repository;

	@Test
	void 존재하는_핸들은_프로필과_최근_콘텐츠를_반환한다() throws Exception {
		given(repository.findProfile("hype_official")).willReturn(Optional.of(
				new V1InfluencerRepository.ProfileRow("hype_official", "하입 오피셜",
						"https://img.example.com/p.jpg", 12345L, "https://hype.example.com",
						321L, 456L, "안녕하세요 하입 오피셜입니다.")));
		given(repository.findRecentCards("hype_official")).willReturn(List.of());

		mockMvc.perform(get("/v1/influencers/hype_official"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.influencer.handle").value("hype_official"))
				.andExpect(jsonPath("$.data.influencer.email").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.data.recentContents").isArray());
	}

	@Test
	void 존재하지_않는_핸들은_404_NOT_FOUND() throws Exception {
		given(repository.findProfile("ghost")).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/influencers/ghost"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}
}
