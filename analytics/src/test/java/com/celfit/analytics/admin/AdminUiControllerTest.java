package com.celfit.analytics.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.mirror.MirrorRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.hamcrest.Matchers;
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

	@MockitoBean
	AnalyticsSettings settings;

	@MockitoBean
	MirrorRegistry mirrorRegistry;

	/** 운영 실측 규모의 퍼널 — 수집 2.7만 → 후보 1,914, 분석 431(매일 57·백필 300·기타 74). */
	private static PipelineStatsService.Funnel funnel(long candidates, long timelyExcluded) {
		return funnel(candidates, timelyExcluded, null);
	}

	private static PipelineStatsService.Funnel funnel(long candidates, long timelyExcluded,
			String candidatesError) {
		return new PipelineStatsService.Funnel(27_093, candidates, timelyExcluded,
				431, 57, 300, 16_686, 77, 1_496, 12,
				candidates < 0 ? 0 : 450, candidates < 0 ? 0 : 5, 3, 2,
				Instant.parse("2026-07-21T08:20:00Z"), candidatesError);
	}

	@BeforeEach
	void stubDefaults() {
		// 진행 스냅샷은 레코드라 Mockito 기본이 null — 카드 조립 NPE 방지용 EMPTY 스텁.
		when(progress.snapshot(any())).thenReturn(new JobProgressRegistry.Progress(false, 0, 0, 0, null));
		when(mirrorRegistry.specs()).thenReturn(java.util.Collections.nCopies(7, null));
		when(settings.llmProvider()).thenReturn("vertex");
		when(settings.activeLlmModel()).thenReturn("gemini-3.1-flash-lite");
		when(stats.health()).thenReturn(new PipelineStatsService.Health(120, 4,
				Instant.now().minus(2, ChronoUnit.HOURS),
				Instant.now().minus(3, ChronoUnit.HOURS),
				Instant.now().minus(5, ChronoUnit.HOURS)));
	}

	@Test
	void ui_페이지는_대시보드_셸을_렌더() throws Exception {
		// 건강·퍼널·잡 카드·피드는 board 프래그먼트(htmx 폴링) 소관 — /ui 자체엔 헤더·셸만.
		mvc.perform(get("/ui"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("hypenow analytics")))
				.andExpect(content().string(Matchers.containsString("/ui/fragments/board")))
				.andExpect(content().string(Matchers.containsString("라이브 로그")));
	}

	@Test
	void 퍼널은_분석완료를_매일과_백필로_분리_노출() throws Exception {
		when(stats.funnel()).thenReturn(funnel(1_914, 24_113));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("1,914")))
				// 매일(랭킹 노출) vs 백필(상세 전용) 분리 — 뭉뚱그린 "분석 완료" 단일 수치가 아니어야 한다
				.andExpect(content().string(Matchers.containsString("분석 완료 내역")))
				.andExpect(content().string(Matchers.containsString("랭킹 노출")))
				.andExpect(content().string(Matchers.containsString("인플루언서 상세 전용")))
				// 매일 57 · 백필 300 · 기타 74(=431-57-300)
				.andExpect(content().string(Matchers.containsString("300")))
				.andExpect(content().string(Matchers.containsString("74")))
				// 커버리지 = timely/후보 = 57/1,914 = 3.0% (전체 431 기준 22.5%가 아니어야 한다)
				.andExpect(content().string(Matchers.containsString("3.0%")))
				.andExpect(content().string(Matchers.containsString("24,113")))
				.andExpect(content().string(Matchers.containsString("pin <span>3</span>일")))
				.andExpect(content().string(Matchers.containsString("slack <span>2</span>일")));
	}

	@Test
	void 후보_집계_중이면_자동갱신_안내를_보여주고_늦크롤_수치는_숨김() throws Exception {
		// candidates·timelyExcluded -1(집계 중) — "-1건" 오표기가 없어야 하고,
		// 백그라운드 소요·자동 갱신을 명시해 "고장"으로 오해되지 않아야 한다.
		when(stats.funnel()).thenReturn(funnel(-1, -1));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("집계 중")))
				.andExpect(content().string(Matchers.containsString("백그라운드 ~3분 · 자동 갱신")))
				.andExpect(content().string(Matchers.containsString("제때 크롤분")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("건은 후보 밖"))));
	}

	@Test
	void 후보_집계_실패는_집계중과_구분해_사유까지_노출() throws Exception {
		// 뷰 드리프트로 후보 뷰가 사라지면 캐시가 없는 채 실패만 반복된다 — 이때 "집계 중"으로 뭉개면
		// 영원히 안 뜨는 것처럼 보인다. 실패임을 명시하고 사유를 그대로 보여줘야 한다.
		when(stats.funnel()).thenReturn(funnel(-1, -1,
				"ERROR: relation \"analytics.v_analysis_candidates\" does not exist"));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("집계 실패")))
				.andExpect(content().string(Matchers.containsString("분석 뷰가 raw DB에 적용되지 않았을 수 있음")))
				.andExpect(content().string(Matchers.containsString("v_analysis_candidates")))
				.andExpect(content().string(Matchers.containsString("5초마다 재시도 중")))
				// 실패 상태에서는 "집계 중…"으로 오인시키지 않는다
				.andExpect(content().string(Matchers.not(Matchers.containsString("백그라운드 ~3분"))));
	}

	@Test
	void 건강_스트립은_프로바이더와_신선도와_오늘처리량() throws Exception {
		when(stats.funnel()).thenReturn(funnel(1_914, 24_113));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("LLM 프로바이더")))
				.andExpect(content().string(Matchers.containsString("Vertex AI")))
				.andExpect(content().string(Matchers.containsString("gemini-3.1-flash-lite")))
				.andExpect(content().string(Matchers.containsString("오늘 처리")))
				.andExpect(content().string(Matchers.containsString("120")))
				// 신선도 — 마지막 분석/미러/계정 카피 3종
				.andExpect(content().string(Matchers.containsString("마지막 분석")))
				.andExpect(content().string(Matchers.containsString("마지막 미러")))
				.andExpect(content().string(Matchers.containsString("마지막 계정 카피")))
				.andExpect(content().string(Matchers.containsString("시간 전")));
	}

	@Test
	void 쿼터_이월이면_상단에_경고() throws Exception {
		// 커버리지가 안 느는 가장 흔한 원인 — 최근 실행이 QUOTA_CARRYOVER면 배너로 노출한다.
		when(stats.funnel()).thenReturn(funnel(1_914, 24_113));
		when(history.recent(anyInt())).thenReturn(List.of(new RunHistory.Run(
				JobName.ANALYZE, TriggerType.SCHEDULED, Instant.now().minusSeconds(600),
				Instant.now(), RunHistory.Outcome.QUOTA_CARRYOVER, 450, 0, null)));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("쿼터 이월 중")))
				.andExpect(content().string(Matchers.containsString("LLM 일 한도 소진")));
	}

	@Test
	void 잡카드는_콘텐츠분석과_계정카피의_대상_완료_잔여를_각각_노출() throws Exception {
		when(stats.funnel()).thenReturn(funnel(1_914, 24_113));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				// 콘텐츠 분석 — 매일 57 완료 · 후보 1,914 · 잔여 1,857
				.andExpect(content().string(Matchers.containsString("매일 57 완료")))
				.andExpect(content().string(Matchers.containsString("잔여 1,857")))
				.andExpect(content().string(Matchers.containsString("백필(상세 전용) 300 별도")))
				// 계정 카피 — 완료 77 / 뷰티 1,496 · 대상 12
				.andExpect(content().string(Matchers.containsString("완료 77 / 뷰티 1,496")))
				.andExpect(content().string(Matchers.containsString("대상 12")))
				// 미러도 "몇 개를 옮겼는지" 보여야 한다 (대상 뷰 수 · 적재 결과)
				.andExpect(content().string(Matchers.containsString("대상 7개 뷰 · 게시물 16,686 · 계정 1,496")));
	}

	@Test
	void 후보_미상이면_오늘_예정량은_0이_아니라_미상으로() throws Exception {
		// todayPlanned=0을 "+0 예정"으로 쓰면 "오늘 아무것도 안 함"으로 오독된다.
		when(stats.funnel()).thenReturn(funnel(-1, -1, "ERROR: relation does not exist"));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("오늘 예정량 미상")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("오늘 +0 예정"))));
	}

	@Test
	void 분석완료_단계는_합계와_매일_백필_내역을_함께_보여준다() throws Exception {
		// 대표 숫자만 보면 뭉쳐 보이므로 단계 바로 아래에 내역을 붙인다.
		when(stats.funnel()).thenReturn(funnel(1_914, 24_113));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("분석 완료 <span class=\"off\">합계</span>")))
				.andExpect(content().string(Matchers.containsString("서빙 미러 <span class=\"off\">게시물</span>")));
	}

	@Test
	void 휴면_댓글분류와_CLI_백필배치도_카드로_노출() throws Exception {
		// 숨기면 "0건 = 고장?"으로 오해되므로 사유와 함께 남긴다.
		when(stats.funnel()).thenReturn(funnel(1_914, 24_113));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("댓글 분류")))
				.andExpect(content().string(Matchers.containsString("휴면")))
				.andExpect(content().string(Matchers.containsString("댓글 수집 재개 대기")))
				.andExpect(content().string(Matchers.containsString("백필 배치")))
				.andExpect(content().string(Matchers.containsString("CLI 전용")));
	}

	@Test
	void 보드는_퍼널_집계_실패에도_카드와_피드를_렌더() throws Exception {
		when(stats.funnel()).thenThrow(new RuntimeException("집계 실패"));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("실행 피드")))
				.andExpect(content().string(Matchers.containsString("콘텐츠 분석")));
	}

	@Test
	void 보드_프래그먼트는_실행중_잡을_진행률과_함께() throws Exception {
		when(stats.funnel()).thenReturn(funnel(1_914, 24_113));
		when(jobService.isRunning(JobName.MIRROR)).thenReturn(true);
		when(progress.snapshot(JobName.MIRROR))
				.thenReturn(new JobProgressRegistry.Progress(true, 3, 0, 10, Instant.now()));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("미러")))
				.andExpect(content().string(Matchers.containsString("콘텐츠 분석")))
				.andExpect(content().string(Matchers.containsString("실행 중")))
				.andExpect(content().string(Matchers.containsString("실행 피드")));
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
				.andExpect(content().string(Matchers.containsString("mirror complete")));
	}
}
