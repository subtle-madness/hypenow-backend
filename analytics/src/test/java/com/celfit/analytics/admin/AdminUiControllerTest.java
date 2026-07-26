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

	/** v3 설계 문서 §1의 07-21 운영 실측 규모 — 계정 축 14,123→1,714→723 (회사 169·비뷰티 822). */
	private static final PipelineStatsService.Accounts ACCOUNTS =
			new PipelineStatsService.Accounts(14_123, 1_714, 723, 169, 822, 1_861);

	/** 콘텐츠 축 실측 — 후보 7,402 = timely 1,435 + 윈도우 5,967, 기분석 7,116 / 미분석 286. */
	private static final PipelineStatsService.Heavy HEAVY = new PipelineStatsService.Heavy(
			7_402, 1_435, 1_432, 5_967, 5_684,
			12_777, 11_072, 4_000, 1_104, 723, 700,
			Instant.parse("2026-07-21T08:20:00Z"));

	/** heavy 유무·실패 사유만 갈아끼우는 픽스처 — 누적(각주)·미러 수치는 §1 실측. */
	private static PipelineStatsService.Funnel funnel(PipelineStatsService.Heavy heavy,
			String candidatesError) {
		long remaining = heavy == null ? -1 : heavy.truePending();
		return new PipelineStatsService.Funnel(31_038,
				16_827, 1_745, 14_637,
				30_358, 1_515, 1_517, 12,
				ACCOUNTS, heavy,
				remaining < 0 ? 0 : (int) remaining, remaining < 0 ? 0 : 1,
				3, 1, candidatesError);
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
		// 건강·보드·잡 카드·피드는 board 프래그먼트(htmx 폴링) 소관 — /ui 자체엔 헤더·셸만.
		mvc.perform(get("/ui"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("hypenow analytics")))
				.andExpect(content().string(Matchers.containsString("/ui/fragments/board")))
				.andExpect(content().string(Matchers.containsString("라이브 로그")));
	}

	@Test
	void 계정_보드는_수집_검증_뷰티_모수와_분해를_노출() throws Exception {
		when(stats.funnel()).thenReturn(funnel(HEAVY, null));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("계정 보드")))
				.andExpect(content().string(Matchers.containsString("14,123")))
				.andExpect(content().string(Matchers.containsString("1,714")))
				.andExpect(content().string(Matchers.containsString("723")))
				// 모수에서 빠지는 이유를 명시 분해 — 회사 169 · 비뷰티(미판정 포함) 822
				.andExpect(content().string(Matchers.containsString("뷰티 회사")))
				.andExpect(content().string(Matchers.containsString("169")))
				.andExpect(content().string(Matchers.containsString("비뷰티·미판정")))
				.andExpect(content().string(Matchers.containsString("822")))
				// 재판정 진행 신호 — 오늘 판정 처리량 (히스토리 G3 부재의 차선)
				.andExpect(content().string(Matchers.containsString("오늘 판정")))
				.andExpect(content().string(Matchers.containsString("1,861")));
	}

	@Test
	void 계정_카피는_현_모수_교집합_기준이고_누적은_각주_G1() throws Exception {
		when(stats.funnel()).thenReturn(funnel(HEAVY, null));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				// 현 모수 723 중 카피 700 — 누적 1,517/723 같은 >100% 표기가 아니어야 한다
				.andExpect(content().string(Matchers.containsString("계정 카피 <b>700</b>")))
				.andExpect(content().string(Matchers.containsString("현 모수 기준")))
				.andExpect(content().string(Matchers.containsString("stale 대상")))
				// 누적 카피는 각주로 강등 (탈락 계정 포함 표기)
				.andExpect(content().string(Matchers.containsString("누적 카피")))
				.andExpect(content().string(Matchers.containsString("1,517")))
				.andExpect(content().string(Matchers.containsString("탈락 계정 포함")));
	}

	@Test
	void 콘텐츠_보드는_트랙별_기분석_미분석_4분할과_진짜_잔여_G1() throws Exception {
		when(stats.funnel()).thenReturn(funnel(HEAVY, null));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("콘텐츠 보드")))
				// 축: 수집 31,038 → 뷰티 서빙 12,777 → 분석 자격 7,402
				.andExpect(content().string(Matchers.containsString("31,038")))
				.andExpect(content().string(Matchers.containsString("12,777")))
				.andExpect(content().string(Matchers.containsString("7,402")))
				// 랭킹 트랙(제때) 1,435 = 기분석 1,432 + 미분석 3
				.andExpect(content().string(Matchers.containsString("랭킹 트랙")))
				.andExpect(content().string(Matchers.containsString("1,435")))
				.andExpect(content().string(Matchers.containsString("1,432")))
				// 상세 트랙(윈도우) 5,967 = 기분석 5,684 + 미분석 283
				.andExpect(content().string(Matchers.containsString("상세 트랙")))
				.andExpect(content().string(Matchers.containsString("5,967")))
				.andExpect(content().string(Matchers.containsString("5,684")))
				.andExpect(content().string(Matchers.containsString("283")))
				// 진짜 잔여 = 자격 ∩ 미분석 286 — 사용자가 알고 싶던 그 숫자
				.andExpect(content().string(Matchers.containsString("진짜 잔여")))
				.andExpect(content().string(Matchers.containsString("286")))
				.andExpect(content().string(Matchers.containsString("완주까지 <b>1</b>일")));
	}

	@Test
	void 커버리지는_현_서빙_모수_기준이고_누적은_각주_G2() throws Exception {
		when(stats.funnel()).thenReturn(funnel(HEAVY, null));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				// 분모·분자 모두 현 서빙 스냅샷: 11,072/12,777 = 86.7%
				.andExpect(content().string(Matchers.containsString("서빙 커버리지")))
				.andExpect(content().string(Matchers.containsString("86.7%")))
				.andExpect(content().string(Matchers.containsString("11,072")))
				// 누적 16,827은 각주로 강등 — 모수 리비전 혼재 명시
				.andExpect(content().string(Matchers.containsString("역대 분석 저장")))
				.andExpect(content().string(Matchers.containsString("16,827")))
				.andExpect(content().string(Matchers.containsString("14,637")))
				// 기타 = 16,827 − 1,745 − 14,637 = 445 (immature·마킹 전 레거시)
				.andExpect(content().string(Matchers.containsString("445")))
				.andExpect(content().string(Matchers.containsString("모수 리비전 혼재")))
				// 자격 밖 분해 — 미성숙 4,000 · 영구 제외 1,104
				.andExpect(content().string(Matchers.containsString("미성숙")))
				.andExpect(content().string(Matchers.containsString("4,000")))
				.andExpect(content().string(Matchers.containsString("영구 제외")))
				.andExpect(content().string(Matchers.containsString("1,104")))
				.andExpect(content().string(Matchers.containsString("pin <span>3</span>일")))
				.andExpect(content().string(Matchers.containsString("slack <span>1</span>일")));
	}

	@Test
	void 집계_중이면_자동갱신_안내를_보여주고_대조_수치는_숨김() throws Exception {
		// heavy 없음(집계 중) — "-1건" 오표기가 없어야 하고, 백그라운드 소요·자동 갱신을 명시해
		// "고장"으로 오해되지 않아야 한다. 트랙 분해·커버리지도 집계 후 표시.
		when(stats.funnel()).thenReturn(funnel(null, null));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("집계 중")))
				.andExpect(content().string(Matchers.containsString("백그라운드 ~3분 · 자동 갱신")))
				.andExpect(content().string(Matchers.containsString("계정 카피 현황 집계 중")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("진짜 잔여"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("-1"))));
	}

	@Test
	void 집계_실패는_집계중과_구분해_사유까지_노출() throws Exception {
		// 뷰 드리프트로 후보 뷰가 사라지면 캐시가 없는 채 실패만 반복된다 — 이때 "집계 중"으로 뭉개면
		// 영원히 안 뜨는 것처럼 보인다. 실패임을 명시하고 사유를 그대로 보여줘야 한다.
		when(stats.funnel()).thenReturn(funnel(null,
				"ERROR: relation \"v_analysis_candidates\" does not exist"));
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
		when(stats.funnel()).thenReturn(funnel(HEAVY, null));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("LLM 프로바이더")))
				.andExpect(content().string(Matchers.containsString("Vertex AI")))
				.andExpect(content().string(Matchers.containsString("gemini-3.1-flash-lite")))
				.andExpect(content().string(Matchers.containsString("오늘 처리")))
				.andExpect(content().string(Matchers.containsString("120")))
				// 신선도 3종 — "마지막 미러"는 실체(크롤 지표 최신)에 맞게 개명(v3 §2-C)
				.andExpect(content().string(Matchers.containsString("마지막 분석")))
				.andExpect(content().string(Matchers.containsString("크롤 지표 신선도")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("마지막 미러"))))
				.andExpect(content().string(Matchers.containsString("마지막 계정 카피")))
				.andExpect(content().string(Matchers.containsString("시간 전")));
	}

	@Test
	void 쿼터_이월이면_상단에_경고() throws Exception {
		// 커버리지가 안 느는 가장 흔한 원인 — 최근 실행이 QUOTA_CARRYOVER면 배너로 노출한다.
		when(stats.funnel()).thenReturn(funnel(HEAVY, null));
		when(history.recent(anyInt())).thenReturn(List.of(new RunHistory.Run(
				JobName.ANALYZE, TriggerType.SCHEDULED, Instant.now().minusSeconds(600),
				Instant.now(), RunHistory.Outcome.QUOTA_CARRYOVER, 450, 0, null)));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("쿼터 이월 중")))
				.andExpect(content().string(Matchers.containsString("LLM 일 한도 소진")));
	}

	@Test
	void 잡카드는_진짜_잔여와_현_모수_카피를_노출() throws Exception {
		when(stats.funnel()).thenReturn(funnel(HEAVY, null));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				// 콘텐츠 분석(timely 트랙만, 2026-07-23 분리) — 1,435 = 기분석 1,432 + 미분석 3
				.andExpect(content().string(Matchers.containsString("후보 1,435 · 기분석 1,432 · 미분석 3")))
				.andExpect(content().string(Matchers.containsString("오늘 +3 예정")))
				// 늦크롤 백필(윈도우 트랙) — 5,967 = 기분석 5,684 + 미분석 283
				.andExpect(content().string(Matchers.containsString("후보 5,967 · 기분석 5,684 · 미분석 283")))
				.andExpect(content().string(Matchers.containsString("오늘 +283 예정")))
				// 계정 카피 — 현 모수 723 중 700 · 대상 12
				.andExpect(content().string(Matchers.containsString("현 모수 723 중 카피 700")))
				.andExpect(content().string(Matchers.containsString("대상 12")))
				// 미러는 미러 세계(analysis DB 적재분) 수치 — raw 현 모수와 다를 수 있다
				.andExpect(content().string(Matchers.containsString("대상 7개 뷰 · 게시물 30,358 · 계정 1,515")));
	}

	@Test
	void 잔여_미상이면_오늘_예정량은_0이_아니라_미상으로() throws Exception {
		// todayPlanned=0을 "+0 예정"으로 쓰면 "오늘 아무것도 안 함"으로 오독된다.
		when(stats.funnel()).thenReturn(funnel(null, "ERROR: relation does not exist"));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("오늘 예정량 미상")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("오늘 +0 예정"))));
	}

	@Test
	void 휴면_댓글분류와_CLI_백필배치도_카드로_노출() throws Exception {
		// 숨기면 "0건 = 고장?"으로 오해되므로 사유와 함께 남긴다.
		when(stats.funnel()).thenReturn(funnel(HEAVY, null));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("댓글 분류")))
				.andExpect(content().string(Matchers.containsString("휴면")))
				.andExpect(content().string(Matchers.containsString("댓글 수집 재개 대기")))
				.andExpect(content().string(Matchers.containsString("백필 배치")))
				.andExpect(content().string(Matchers.containsString("CLI 전용")));
	}

	@Test
	void 보드는_집계_실패에도_카드와_피드를_렌더() throws Exception {
		when(stats.funnel()).thenThrow(new RuntimeException("집계 실패"));
		mvc.perform(get("/ui/fragments/board"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("실행 피드")))
				.andExpect(content().string(Matchers.containsString("콘텐츠 분석")));
	}

	@Test
	void 보드_프래그먼트는_실행중_잡을_진행률과_함께() throws Exception {
		when(stats.funnel()).thenReturn(funnel(HEAVY, null));
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
