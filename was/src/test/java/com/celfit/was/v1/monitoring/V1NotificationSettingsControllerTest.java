package com.celfit.was.v1.monitoring;

import static org.mockito.ArgumentMatchers.anyLong;
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
import com.celfit.was.monitoring.WeeklyEmailOptOutRepository;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** /v1/notification-settings 계약(2026-08-27 개편 §5) — 주간 이메일 토글 1개, 400 검증. */
@WebMvcTest(controllers = V1NotificationSettingsController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({NotificationSettingsService.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1NotificationSettingsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	WeeklyEmailOptOutRepository repository;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@Test
	void GET_옵트아웃이_없으면_weeklyEmail_true() throws Exception {
		given(repository.isOptedOut(7L)).willReturn(false);

		mockMvc.perform(get("/v1/notification-settings").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.weeklyEmail").value(true));
	}

	@Test
	void GET_옵트아웃이_있으면_weeklyEmail_false() throws Exception {
		given(repository.isOptedOut(7L)).willReturn(true);

		mockMvc.perform(get("/v1/notification-settings").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.weeklyEmail").value(false));
	}

	@Test
	void PATCH_false는_optOut_호출() throws Exception {
		given(repository.isOptedOut(7L)).willReturn(true);

		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"weeklyEmail":false}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.weeklyEmail").value(false));

		then(repository).should().optOut(7L);
	}

	@Test
	void PATCH_true는_optIn_호출() throws Exception {
		given(repository.isOptedOut(7L)).willReturn(false);

		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"weeklyEmail":true}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.weeklyEmail").value(true));

		then(repository).should().optIn(7L);
	}

	@Test
	void PATCH_구_계약인_content_키는_400() throws Exception {
		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":{"collection_ended":{"email":false}}}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).optOut(anyLong());
		then(repository).should(never()).optIn(anyLong());
	}

	@Test
	void PATCH_weeklyEmail이_boolean이_아니면_400() throws Exception {
		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"weeklyEmail":"false"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).optOut(anyLong());
	}

	@Test
	void PATCH_빈_바디는_변경_없이_현재_상태_반환() throws Exception {
		given(repository.isOptedOut(7L)).willReturn(true);

		mockMvc.perform(patch("/v1/notification-settings").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.weeklyEmail").value(false));

		then(repository).should(never()).optOut(anyLong());
		then(repository).should(never()).optIn(anyLong());
	}
}
