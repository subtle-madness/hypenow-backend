package com.celfit.was.v2.monitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /v2/monitoring/campaigns/{id}/contents 표면 계약(스펙 §8) — 200/202 분기, envelope, 명시적 null,
 * 204·404를 고정한다. 판정은 {@link V2CampaignContentServiceTest}가 끝냈으므로 서비스는 mock이다.
 */
@WebMvcTest(controllers = V2CampaignContentsController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000"})
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V2CampaignContentsControllerTest {

	private static final String CONTENTS = "/v2/monitoring/campaigns/42/contents";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V2CampaignContentService service;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@Test
	void 동기_완결이면_200이다() throws Exception {
		given(service.add(eq(7L), eq(42L), any(), any())).willReturn(new V2CampaignContentService.Added(
				new V2CampaignContentsResponse("42", List.of(
						new V2CampaignContentsResponse.Result("ABC", "success", "11", null, null))),
				false));

		mockMvc.perform(post(CONTENTS).with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contentIds\":[\"ABC\"],\"trackingDays\":30}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data.campaignId").value("42"))
				.andExpect(jsonPath("$.data.results[0].contentId").value("ABC"))
				.andExpect(jsonPath("$.data.results[0].result").value("success"))
				.andExpect(jsonPath("$.data.results[0].monitoringItemId").value("11"))
				// 계약 무결성 규칙 #1 — 해당 없음도 키를 생략하지 않고 명시적 null이다.
				.andExpect(jsonPath("$.data.results[0]", Matchers.hasKey("reasonCode")))
				.andExpect(jsonPath("$.data.results[0].reasonCode").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data.results[0]", Matchers.hasKey("reason")))
				.andExpect(jsonPath("$.data.results[0].reason").value(Matchers.nullValue()));
	}

	@Test
	void 아이템_생성이_섞이면_202다() throws Exception {
		given(service.add(eq(7L), eq(42L), eq(List.of("ABC")), eq(30))).willReturn(
				new V2CampaignContentService.Added(new V2CampaignContentsResponse("42", List.of(
						new V2CampaignContentsResponse.Result("ABC", "pending", "11", null, null))), true));

		mockMvc.perform(post(CONTENTS).with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contentIds\":[\"ABC\"],\"trackingDays\":30}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.results[0].result").value("pending"));
	}

	@Test
	void 부분_성공은_entry로_내려간다() throws Exception {
		given(service.add(eq(7L), eq(42L), any(), any())).willReturn(new V2CampaignContentService.Added(
				new V2CampaignContentsResponse("42", List.of(
						new V2CampaignContentsResponse.Result("ABC", "success", "11", null, null),
						new V2CampaignContentsResponse.Result("DEF", "duplicate", "12",
								"CAMPAIGN_CONTENT_ALREADY_EXISTS", "이미 이 캠페인에 추가된 콘텐츠입니다."),
						new V2CampaignContentsResponse.Result("GHI", "failed", null, "NOT_FOUND",
								"게시물을 찾을 수 없습니다."))),
				false));

		mockMvc.perform(post(CONTENTS).with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contentIds\":[\"ABC\",\"DEF\",\"GHI\"],\"trackingDays\":30}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.results.length()").value(3))
				.andExpect(jsonPath("$.data.results[1].reasonCode").value("CAMPAIGN_CONTENT_ALREADY_EXISTS"))
				.andExpect(jsonPath("$.data.results[2].reason").value("게시물을 찾을 수 없습니다."))
				.andExpect(jsonPath("$.data.results[2].monitoringItemId").value(Matchers.nullValue()));
	}

	@Test
	void 본문이_없어도_서비스_검증으로_400이다() throws Exception {
		willThrow(V1ApiException.validation("추가할 콘텐츠를 입력해 주세요."))
				.given(service).add(eq(7L), eq(42L), any(), any());

		mockMvc.perform(post(CONTENTS).with(user(principal())).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("추가할 콘텐츠를 입력해 주세요."));
	}

	@Test
	void 남의_캠페인은_404다() throws Exception {
		willThrow(V1ApiException.notFound("캠페인을 찾을 수 없습니다."))
				.given(service).add(eq(7L), eq(42L), any(), any());

		mockMvc.perform(post(CONTENTS).with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contentIds\":[\"ABC\"],\"trackingDays\":30}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 숫자가_아닌_캠페인_id는_서비스를_거치지_않고_404다() throws Exception {
		mockMvc.perform(post("/v2/monitoring/campaigns/abc/contents").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contentIds\":[\"ABC\"],\"trackingDays\":30}"))
				.andExpect(status().isNotFound());

		then(service).should(never()).add(anyLong(), anyLong(), any(), any());
	}

	@Test
	void 제거는_204다() throws Exception {
		mockMvc.perform(delete(CONTENTS + "/ABC").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(service).should().remove(7L, 42L, "ABC");
	}

	@Test
	void 캠페인에_없는_콘텐츠_제거는_404다() throws Exception {
		willThrow(V1ApiException.notFound("캠페인에서 콘텐츠를 찾을 수 없습니다."))
				.given(service).remove(anyLong(), anyLong(), anyString());

		mockMvc.perform(delete(CONTENTS + "/ABC").with(user(principal())).with(csrf()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.message").value("캠페인에서 콘텐츠를 찾을 수 없습니다."));
	}

	@Test
	void 비로그인은_401이다() throws Exception {
		mockMvc.perform(post(CONTENTS).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contentIds\":[\"ABC\"],\"trackingDays\":30}"))
				.andExpect(status().isUnauthorized());
	}
}
