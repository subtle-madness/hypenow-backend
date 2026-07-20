package com.celfit.was.v1.saved;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.celfit.was.v1.content.ContentCardAssembler;
import com.celfit.was.v1.content.ContentCardRow;
import com.celfit.was.v1.saved.V1SavedRepository.ContentSave;
import com.celfit.was.v1.saved.V1SavedRepository.SavedContentRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** /v1/saved-contents 계약(스펙 6.6~6.8) — 201/200/404/400/204·memo 정규화·저장순·미러 제외. */
@WebMvcTest(controllers = V1SavedContentsController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({ContentCardAssembler.class, V1SavedAssembler.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1SavedContentsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1SavedRepository repository;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static ContentCardRow row(String code) {
		return new ContentCardRow(code, "https://thumb/" + code, "캡션",
				OffsetDateTime.parse("2026-07-02T03:00:00Z"), "reels", new BigDecimal("20"),
				"https://ig/" + code, 1000L, 100L, 10L, 500L,
				OffsetDateTime.parse("2026-07-05T03:00:00Z"), "makeup", null, "organic", null, null, null,
				"alpha", "알파", "https://pic/alpha.jpg", 5000L);
	}

	@Test
	void 신규_저장은_201이고_카드_조합_항목을_돌려준다() throws Exception {
		given(repository.findCard("c1")).willReturn(Optional.of(row("c1")));
		given(repository.upsertContent(7L, "c1", "협업 후보")).willReturn(
				new ContentSave("c1", "협업 후보", OffsetDateTime.parse("2026-07-15T00:00:00Z"), true));

		mockMvc.perform(post("/v1/saved-contents").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"contentId":"c1","memo":"협업 후보"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.content.id").value("c1"))
				.andExpect(jsonPath("$.data.content.isContentsSaved").value(true)) // 저장 응답 카드(스펙 6.7)
				.andExpect(jsonPath("$.data.memo").value("협업 후보"))
				.andExpect(jsonPath("$.data.savedAt").value("2026-07-15T00:00:00Z"));
	}

	@Test
	void 재저장은_200이다() throws Exception {
		given(repository.findCard("c1")).willReturn(Optional.of(row("c1")));
		given(repository.upsertContent(anyLong(), anyString(), any())).willReturn(
				new ContentSave("c1", null, OffsetDateTime.parse("2026-07-10T00:00:00Z"), false));

		mockMvc.perform(post("/v1/saved-contents").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"contentId":"c1"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.memo").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void 공백_memo는_정규화되어_null로_저장된다() throws Exception {
		given(repository.findCard("c1")).willReturn(Optional.of(row("c1")));
		given(repository.upsertContent(anyLong(), anyString(), any())).willReturn(
				new ContentSave("c1", null, OffsetDateTime.parse("2026-07-10T00:00:00Z"), true));

		mockMvc.perform(post("/v1/saved-contents").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"contentId":"c1","memo":"   "}"""))
				.andExpect(status().isCreated());

		ArgumentCaptor<String> memo = ArgumentCaptor.forClass(String.class);
		then(repository).should().upsertContent(eq(7L), eq("c1"), memo.capture());
		assertThat(memo.getValue()).isNull();
	}

	@Test
	void 없는_콘텐츠_저장은_404_NOT_FOUND다() throws Exception {
		given(repository.findCard("ghost")).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/saved-contents").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"contentId":"ghost"}"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("콘텐츠를 찾을 수 없습니다."));

		then(repository).should(never()).upsertContent(anyLong(), anyString(), any());
	}

	@Test
	void contentId_누락은_400_VALIDATION_FAILED다() throws Exception {
		mockMvc.perform(post("/v1/saved-contents").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"memo":"메모만"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).findCard(anyString());
	}

	@Test
	void 목록은_최근순이며_미러에_없는_저장은_제외하고_total은_노출_건수다() throws Exception {
		OffsetDateTime t = OffsetDateTime.parse("2026-07-15T00:00:00Z");
		given(repository.findSavedContents(7L)).willReturn(List.of(
				new SavedContentRow("c2", "메모2", t),
				new SavedContentRow("gone", null, t), // 미러 부재 → 제외
				new SavedContentRow("c1", null, t)));
		given(repository.findCards(List.of("c2", "gone", "c1"))).willReturn(List.of(row("c1"), row("c2")));

		mockMvc.perform(get("/v1/saved-contents").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].content.id").value("c2")) // 저장순 유지
				.andExpect(jsonPath("$.data[0].content.isContentsSaved").value(true)) // 저장 목록 카드(스펙 6.6)
				.andExpect(jsonPath("$.data[1].content.id").value("c1"))
				.andExpect(jsonPath("$.meta.total").value(2)) // 제외분(gone) 미포함
				.andExpect(jsonPath("$.meta.limit").value(100));
	}

	@Test
	void 빈_저장_목록은_analysis_조회_없이_빈_배열이다() throws Exception {
		given(repository.findSavedContents(7L)).willReturn(List.of());

		mockMvc.perform(get("/v1/saved-contents").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0))
				.andExpect(jsonPath("$.meta.total").value(0));

		then(repository).should(never()).findCards(any());
	}

	@Test
	void 삭제는_멱등_204다() throws Exception {
		mockMvc.perform(delete("/v1/saved-contents/c1").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(repository).should().deleteContent(7L, "c1");
	}

	@Test
	void 메모_수정은_200이고_카드_조합_항목을_돌려준다() throws Exception {
		given(repository.updateContentMemo(7L, "c1", "수정된 메모")).willReturn(
				Optional.of(new SavedContentRow("c1", "수정된 메모", OffsetDateTime.parse("2026-07-15T00:00:00Z"))));
		given(repository.findCard("c1")).willReturn(Optional.of(row("c1")));

		mockMvc.perform(put("/v1/saved-contents/c1").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"memo":"수정된 메모"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.id").value("c1"))
				.andExpect(jsonPath("$.data.content.isContentsSaved").value(true))
				.andExpect(jsonPath("$.data.memo").value("수정된 메모"))
				.andExpect(jsonPath("$.data.savedAt").value("2026-07-15T00:00:00Z"));
	}

	@Test
	void 공백_memo_수정은_정규화되어_null로_저장된다() throws Exception {
		given(repository.updateContentMemo(eq(7L), eq("c1"), any())).willReturn(
				Optional.of(new SavedContentRow("c1", null, OffsetDateTime.parse("2026-07-15T00:00:00Z"))));
		given(repository.findCard("c1")).willReturn(Optional.of(row("c1")));

		mockMvc.perform(put("/v1/saved-contents/c1").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"memo":"   "}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.memo").value(org.hamcrest.Matchers.nullValue()));

		ArgumentCaptor<String> memo = ArgumentCaptor.forClass(String.class);
		then(repository).should().updateContentMemo(eq(7L), eq("c1"), memo.capture());
		assertThat(memo.getValue()).isNull();
	}

	@Test
	void 저장_안_된_콘텐츠_메모_수정은_404_NOT_FOUND다() throws Exception {
		given(repository.updateContentMemo(7L, "ghost", null)).willReturn(Optional.empty());

		mockMvc.perform(put("/v1/saved-contents/ghost").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"memo":null}"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("저장된 콘텐츠가 아닙니다."));

		then(repository).should(never()).findCard(anyString());
	}
}
