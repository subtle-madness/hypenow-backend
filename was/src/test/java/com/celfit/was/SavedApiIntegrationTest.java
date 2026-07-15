package com.celfit.was;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

/** 저장 API(인플루언서 후보·콘텐츠 북마크) — 인증 필수, 상태 전이·메모 부분 갱신 계약을 실 DB로 검증한다. */
@AutoConfigureMockMvc
class SavedApiIntegrationTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	/** email로 가입 후 로그인해 세션을 반환한다 — 저장 API는 전부 인증 필수라 매 테스트 새 계정을 쓴다. */
	private MockHttpSession signUpAndLogIn(String email) throws Exception {
		mockMvc.perform(post("/api/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
				.andExpect(status().isCreated());

		MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

	@Test
	void 인플루언서_PUT은_기본값_reviewing이고_상태_변경도_반영한다() throws Exception {
		MockHttpSession session = signUpAndLogIn("influencer-put@example.com");

		mockMvc.perform(put("/api/saved/influencers/alpha").with(csrf()).session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.handle").value("alpha"))
				.andExpect(jsonPath("$.status").value("reviewing"))
				.andExpect(jsonPath("$.memo").doesNotExist());

		mockMvc.perform(put("/api/saved/influencers/alpha").with(csrf()).session(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"contact_planned\",\"memo\":\"연락 예정\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("contact_planned"))
				.andExpect(jsonPath("$.memo").value("연락 예정"));
	}

	@Test
	void 어휘_밖_상태는_400이다() throws Exception {
		MockHttpSession session = signUpAndLogIn("influencer-bad-status@example.com");

		mockMvc.perform(put("/api/saved/influencers/alpha").with(csrf()).session(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"not-a-status\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 인플루언서_목록_조회와_삭제() throws Exception {
		MockHttpSession session = signUpAndLogIn("influencer-list@example.com");
		mockMvc.perform(put("/api/saved/influencers/alpha").with(csrf()).session(session))
				.andExpect(status().isOk());
		mockMvc.perform(put("/api/saved/influencers/beta").with(csrf()).session(session))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/saved/influencers").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2));

		mockMvc.perform(delete("/api/saved/influencers/alpha").with(csrf()).session(session))
				.andExpect(status().isNoContent());
		// 다시 지워도 멱등하게 204
		mockMvc.perform(delete("/api/saved/influencers/alpha").with(csrf()).session(session))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/saved/influencers").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].handle").value("beta"));
	}

	@Test
	void 콘텐츠_저장_조회_삭제() throws Exception {
		MockHttpSession session = signUpAndLogIn("content-crud@example.com");

		mockMvc.perform(put("/api/saved/contents/h1").with(csrf()).session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shortCode").value("h1"));
		// 멱등 upsert — 재요청도 200
		mockMvc.perform(put("/api/saved/contents/h1").with(csrf()).session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shortCode").value("h1"));

		mockMvc.perform(get("/api/saved/contents").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].shortCode").value("h1"));

		mockMvc.perform(delete("/api/saved/contents/h1").with(csrf()).session(session))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/saved/contents/h1").with(csrf()).session(session))
				.andExpect(status().isNoContent()); // 멱등

		mockMvc.perform(get("/api/saved/contents").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void 미로그인_인플루언서_PUT은_401이다() throws Exception {
		mockMvc.perform(put("/api/saved/influencers/alpha").with(csrf()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 미로그인_콘텐츠_GET은_401이다() throws Exception {
		mockMvc.perform(get("/api/saved/contents"))
				.andExpect(status().isUnauthorized());
	}
}
