package com.celfit.analytics.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
	JobProgressRegistry progress;

	@MockitoBean
	RunHistory history;

	@MockitoBean
	PipelineStatsService stats;

	@MockitoBean
	ScheduleInfo scheduleInfo;

	@MockitoBean
	LogBuffer logBuffer;

	@BeforeEach
	void stubDefaults() {
		// 진행 스냅샷은 레코드라 Mockito 기본이 null — 카드 조립 NPE 방지용 EMPTY 스텁.
		when(progress.snapshot(any())).thenReturn(new JobProgressRegistry.Progress(false, 0, 0, 0, null));
	}

	@Test
	void ui_페이지는_대시보드_셸을_렌더() throws Exception {
		// 잡 카드·피드는 board 프래그먼트(htmx 로드) 소관 — /ui 자체엔 헤더·퍼널·로그 셸만.
		mvc.perform(get("/ui"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("hypenow analytics")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("/ui/fragments/board")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("라이브 로그")));
	}

	@Test
	void ui_페이지는_퍼널_집계_실패에도_렌더() throws Exception {
		when(stats.funnel()).thenThrow(new RuntimeException("집계 실패"));
		mvc.perform(get("/ui"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("hypenow analytics")));
	}

	@Test
	void 보드_프래그먼트는_카드와_피드() throws Exception {
		when(jobService.isRunning(JobName.MIRROR)).thenReturn(true);
		when(progress.snapshot(JobName.MIRROR))
				.thenReturn(new JobProgressRegistry.Progress(true, 3, 0, 10, Instant.now()));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("미러")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("콘텐츠 분석")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("실행 중")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("실행 피드")));
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
}
