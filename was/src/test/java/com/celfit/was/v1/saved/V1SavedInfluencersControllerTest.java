package com.celfit.was.v1.saved;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.content.ContentCard;
import com.celfit.was.v1.content.ContentCardAssembler;
import com.celfit.was.v1.saved.V1SavedRepository.InfluencerSave;
import com.celfit.was.v1.saved.V1SavedRepository.SavedInfluencerRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** /v1/saved-influencers 계약(스펙 6.9~6.11) — 201/200/404/400/204·미러 부재 handle-only 노출. */
@WebMvcTest(controllers = V1SavedInfluencersController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({ContentCardAssembler.class, V1SavedAssembler.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1SavedInfluencersControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1SavedRepository repository;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static ContentCard.Influencer profile(String handle) {
		return new ContentCard.Influencer(handle, handle, "알파", "https://pic/" + handle + ".jpg", 5000L);
	}

	@Test
	void 신규_저장은_201이고_프로필_조합_항목을_돌려준다() throws Exception {
		given(repository.findInfluencer("alpha")).willReturn(Optional.of(profile("alpha")));
		given(repository.upsertInfluencer(7L, "alpha", "1순위")).willReturn(
				new InfluencerSave("alpha", "1순위", OffsetDateTime.parse("2026-07-15T00:00:00Z"), true));

		mockMvc.perform(post("/v1/saved-influencers").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"influencerId":"alpha","memo":"1순위"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.influencer.id").value("alpha"))
				.andExpect(jsonPath("$.data.influencer.handle").value("alpha"))
				.andExpect(jsonPath("$.data.influencer.displayName").value("알파"))
				.andExpect(jsonPath("$.data.influencer.followers").value(5000))
				.andExpect(jsonPath("$.data.memo").value("1순위"))
				.andExpect(jsonPath("$.data.savedAt").value("2026-07-15T00:00:00Z"));
	}

	@Test
	void 재저장은_200이다() throws Exception {
		given(repository.findInfluencer("alpha")).willReturn(Optional.of(profile("alpha")));
		given(repository.upsertInfluencer(anyLong(), anyString(), any())).willReturn(
				new InfluencerSave("alpha", "보류", OffsetDateTime.parse("2026-07-10T00:00:00Z"), false));

		mockMvc.perform(post("/v1/saved-influencers").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"influencerId":"alpha","memo":"보류"}"""))
				.andExpect(status().isOk());
	}

	@Test
	void 없는_인플루언서_저장은_404_NOT_FOUND다() throws Exception {
		given(repository.findInfluencer("ghost")).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/saved-influencers").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"influencerId":"ghost"}"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("인플루언서를 찾을 수 없습니다."));

		then(repository).should(never()).upsertInfluencer(anyLong(), anyString(), any());
	}

	@Test
	void influencerId_누락은_400_VALIDATION_FAILED다() throws Exception {
		mockMvc.perform(post("/v1/saved-influencers").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"memo":"메모만"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).findInfluencer(anyString());
	}

	@Test
	void 목록은_최근순이고_미러에_없는_인플루언서는_handle만_채워_남긴다() throws Exception {
		OffsetDateTime t = OffsetDateTime.parse("2026-07-15T00:00:00Z");
		given(repository.findSavedInfluencers(7L)).willReturn(List.of(
				new SavedInfluencerRow("beta", "메모", t),
				new SavedInfluencerRow("ghost", null, t))); // accounts에 없음
		given(repository.findInfluencers(List.of("beta", "ghost"))).willReturn(List.of(profile("beta")));

		mockMvc.perform(get("/v1/saved-influencers").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2)) // 제외 없음
				.andExpect(jsonPath("$.data[0].influencer.handle").value("beta"))
				.andExpect(jsonPath("$.data[1].influencer.handle").value("ghost"))
				.andExpect(jsonPath("$.data[1].influencer.displayName").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.meta.total").value(2))
				.andExpect(jsonPath("$.meta.limit").value(100));
	}

	@Test
	void 빈_저장_목록은_analysis_조회_없이_빈_배열이다() throws Exception {
		given(repository.findSavedInfluencers(7L)).willReturn(List.of());

		mockMvc.perform(get("/v1/saved-influencers").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0));

		then(repository).should(never()).findInfluencers(any());
	}

	@Test
	void 삭제는_멱등_204다() throws Exception {
		mockMvc.perform(delete("/v1/saved-influencers/alpha").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(repository).should().deleteInfluencer(7L, "alpha");
	}

	@Test
	void 메모_수정은_200이고_프로필_조합_항목을_돌려준다() throws Exception {
		given(repository.updateInfluencerMemo(7L, "alpha", "수정된 메모")).willReturn(
				Optional.of(new SavedInfluencerRow("alpha", "수정된 메모", OffsetDateTime.parse("2026-07-15T00:00:00Z"))));
		given(repository.findInfluencer("alpha")).willReturn(Optional.of(profile("alpha")));

		mockMvc.perform(put("/v1/saved-influencers/alpha").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"memo":"수정된 메모"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.influencer.handle").value("alpha"))
				.andExpect(jsonPath("$.data.influencer.displayName").value("알파"))
				.andExpect(jsonPath("$.data.memo").value("수정된 메모"))
				.andExpect(jsonPath("$.data.savedAt").value("2026-07-15T00:00:00Z"));
	}

	@Test
	void 미러에_없는_인플루언서_메모_수정도_handle만_채워_200이다() throws Exception {
		given(repository.updateInfluencerMemo(7L, "ghost", "메모")).willReturn(
				Optional.of(new SavedInfluencerRow("ghost", "메모", OffsetDateTime.parse("2026-07-15T00:00:00Z"))));
		given(repository.findInfluencer("ghost")).willReturn(Optional.empty()); // accounts 미러 부재

		mockMvc.perform(put("/v1/saved-influencers/ghost").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"memo":"메모"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.influencer.handle").value("ghost"))
				.andExpect(jsonPath("$.data.influencer.displayName").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.data.memo").value("메모"));
	}

	@Test
	void 저장_안_된_인플루언서_메모_수정은_404_NOT_FOUND다() throws Exception {
		given(repository.updateInfluencerMemo(7L, "ghost", null)).willReturn(Optional.empty());

		mockMvc.perform(put("/v1/saved-influencers/ghost").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"memo":null}"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("저장된 인플루언서가 아닙니다."));

		then(repository).should(never()).findInfluencer(anyString());
	}
}
