package com.celfit.was.v1.influencer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.SavedLookup;
import com.celfit.was.v1.content.ContentCardAssembler;
import com.celfit.was.v1.content.ContentCardRow;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

	@MockitoBean
	SavedLookup savedLookup;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static V1InfluencerRepository.ProfileRow profile() {
		return new V1InfluencerRepository.ProfileRow("hype_official", "하입 오피셜",
				"https://img.example.com/p.jpg", 12345L, "https://hype.example.com",
				321L, 456L, "안녕하세요 하입 오피셜입니다.");
	}

	private static ContentCardRow row(String code) {
		return new ContentCardRow(code, "https://thumb/" + code, "캡션",
				OffsetDateTime.parse("2026-07-02T03:00:00Z"), "reels", new BigDecimal("20"),
				"https://ig/" + code, 1000L, 100L, 10L, 500L,
				OffsetDateTime.parse("2026-07-05T03:00:00Z"), "makeup", null, "organic", null, null, null,
				"hype_official", "하입 오피셜", "https://pic/hype.jpg", 12345L);
	}

	@Test
	void 존재하는_핸들은_프로필과_최근_콘텐츠를_반환한다() throws Exception {
		given(repository.findProfile("hype_official")).willReturn(Optional.of(profile()));
		given(repository.findRecentCards("hype_official")).willReturn(List.of());

		mockMvc.perform(get("/v1/influencers/hype_official").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.influencer.handle").value("hype_official"))
				.andExpect(jsonPath("$.data.influencer.email").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.data.recentContents").isArray());
	}

	@Test
	void 존재하지_않는_핸들은_404_NOT_FOUND() throws Exception {
		given(repository.findProfile("ghost")).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/influencers/ghost").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	// 로그인 월(07-17) — 읽기도 인증 필수라 "비로그인 개인화 필드 없음" 계약은 401로 대체됐다
	@Test
	void 비로그인은_401_UNAUTHORIZED_envelope다() throws Exception {
		mockMvc.perform(get("/v1/influencers/hype_official"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void 로그인이면_개인화_필드를_채운다() throws Exception {
		given(repository.findProfile("hype_official")).willReturn(Optional.of(profile()));
		given(repository.findRecentCards("hype_official")).willReturn(List.of(row("c1"), row("c2")));
		given(savedLookup.savedShortCodes(7L)).willReturn(Set.of("c1"));
		given(savedLookup.isInfluencerSaved(7L, "hype_official")).willReturn(true);

		mockMvc.perform(get("/v1/influencers/hype_official").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.isInfluencerSaved").value(true))
				.andExpect(jsonPath("$.data.recentContents[0].isContentsSaved").value(true))
				.andExpect(jsonPath("$.data.recentContents[1].isContentsSaved").value(false));
	}
}
