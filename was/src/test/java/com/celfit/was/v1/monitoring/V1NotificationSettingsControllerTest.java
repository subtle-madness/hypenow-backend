package com.celfit.was.v1.monitoring;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.monitoring.EmailOptOutRepository;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** /v1/notification-settings 계약(스펙 6.33) — 4종 완전체 GET, 부분 머지 PATCH, 400 검증. */
@WebMvcTest(controllers = V1NotificationSettingsController.class, properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({NotificationSettingsService.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1NotificationSettingsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	EmailOptOutRepository repository;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@Test
	void GET_옵트아웃_없으면_4종_전부_true() throws Exception {
		given(repository.findOptOuts(7L)).willReturn(Set.of());

		mockMvc.perform(get("/v1/notification-settings").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.collection_started.email").value(true))
				.andExpect(jsonPath("$.data.content.collection_ended.email").value(true))
				.andExpect(jsonPath("$.data.content.metrics_private.email").value(true))
				.andExpect(jsonPath("$.data.content.content_issue.email").value(true));
	}

	@Test
	void GET_lazy_생성_전에도_4종_키_완전체다() throws Exception {
		// 옵트아웃 행이 아예 없는(lazy 생성 전) 유저 — repository는 빈 집합만 돌려준다.
		given(repository.findOptOuts(7L)).willReturn(Set.of());

		mockMvc.perform(get("/v1/notification-settings").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.length()").value(4));
	}

	@Test
	void GET_옵트아웃_행이_있으면_해당_이벤트만_false() throws Exception {
		given(repository.findOptOuts(7L)).willReturn(Set.of("collection_ended"));

		mockMvc.perform(get("/v1/notification-settings").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.collection_ended.email").value(false))
				.andExpect(jsonPath("$.data.content.collection_started.email").value(true))
				.andExpect(jsonPath("$.data.content.metrics_private.email").value(true))
				.andExpect(jsonPath("$.data.content.content_issue.email").value(true));
	}

	@Test
	void PATCH_email_false는_optOut_호출() throws Exception {
		given(repository.findOptOuts(7L)).willReturn(Set.of("collection_ended"));

		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":{"collection_ended":{"email":false}}}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.collection_ended.email").value(false))
				.andExpect(jsonPath("$.data.content.collection_started.email").value(true))
				.andExpect(jsonPath("$.data.content.metrics_private.email").value(true))
				.andExpect(jsonPath("$.data.content.content_issue.email").value(true));

		then(repository).should().optOut(7L, "collection_ended");
	}

	@Test
	void PATCH_email_true는_optIn_호출() throws Exception {
		given(repository.findOptOuts(7L)).willReturn(Set.of());

		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":{"collection_ended":{"email":true}}}"""))
				.andExpect(status().isOk());

		then(repository).should().optIn(7L, "collection_ended");
	}

	@Test
	void PATCH_미지_이벤트_키는_400() throws Exception {
		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":{"unknown_event":{"email":false}}}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).optOut(anyLong(), anyString());
		then(repository).should(never()).optIn(anyLong(), anyString());
	}

	@Test
	void PATCH_content_밖_최상위_키는_400() throws Exception {
		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"unexpected":{"collection_ended":{"email":false}}}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).optOut(anyLong(), anyString());
	}

	@Test
	void PATCH_email이_boolean_아니면_400() throws Exception {
		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":{"collection_ended":{"email":"false"}}}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).optOut(anyLong(), anyString());
	}

	@Test
	void PATCH_email이_아닌_채널_키는_400() throws Exception {
		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":{"collection_ended":{"push":true}}}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).optOut(anyLong(), anyString());
	}

	@Test
	void PATCH_빈_바디는_변경_없이_현재_상태_반환() throws Exception {
		given(repository.findOptOuts(7L)).willReturn(Set.of("metrics_private"));

		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.metrics_private.email").value(false));

		then(repository).should(never()).optOut(anyLong(), anyString());
		then(repository).should(never()).optIn(anyLong(), anyString());
	}
}
