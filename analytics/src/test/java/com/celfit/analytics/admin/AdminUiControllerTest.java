package com.celfit.analytics.admin;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminUiController.class)
@TestPropertySource(properties = "analytics.admin-enabled=true") // 컨트롤러의 @ConditionalOnProperty 게이트
class AdminUiControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	AnalyticsJobService jobService;

	@MockitoBean
	LogBuffer logBuffer;

	@Test
	void ui_페이지는_잡_버튼을_렌더() throws Exception {
		mvc.perform(get("/ui"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("미러")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("콘텐츠 분석")));
	}

	@Test
	void 트리거는_서비스를_부르고_리다이렉트() throws Exception {
		when(jobService.trigger(eq(JobName.MIRROR), eq(TriggerType.MANUAL)))
				.thenReturn(AnalyticsJobService.TriggerResult.ACCEPTED);
		mvc.perform(post("/ui/jobs/mirror"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/ui"))
				.andExpect(flash().attributeExists("message"));
		verify(jobService).trigger(JobName.MIRROR, TriggerType.MANUAL);
	}

	@Test
	void account_analyze_슬러그도_매핑() throws Exception {
		when(jobService.trigger(eq(JobName.ACCOUNT_ANALYZE), eq(TriggerType.MANUAL)))
				.thenReturn(AnalyticsJobService.TriggerResult.BUSY);
		mvc.perform(post("/ui/jobs/account-analyze"))
				.andExpect(status().is3xxRedirection());
	}

	@Test
	void 모르는_잡은_404() throws Exception {
		mvc.perform(post("/ui/jobs/nope")).andExpect(status().isNotFound());
	}

	@Test
	void 로그_프래그먼트() throws Exception {
		when(logBuffer.lines()).thenReturn(List.of(
				new LogBuffer.Line("12:00:00", "INFO", "MirrorJob", "mirror complete")));
		mvc.perform(get("/ui/fragments/logs"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("mirror complete")));
	}

	@Test
	void 상태_프래그먼트는_실행_중_배지() throws Exception {
		when(jobService.isRunning(JobName.MIRROR)).thenReturn(true);
		mvc.perform(get("/ui/fragments/status"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("실행 중")));
	}
}
