package com.celfit.monitoring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.service.CollectService;
import com.celfit.monitoring.service.DailySweepJob;
import com.celfit.monitoring.store.SweepRunRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 일일 스윕 수동 트리거 API — POST /api/sweeps(비동기 시작) · GET /api/sweeps/latest(상태 조회).
 * 실제 DailySweepJob 대신 {@link ControllableSweepJob}(runWithId를 오버라이드해 타이밍을 통제하는
 * 서브클래스, target이 하나도 없으니 실행 자체는 아무 Hiker 콜도 내지 않는다)을 @Primary로 꽂아
 * "동시 실행 가드가 크론/수동 경로 없이도 단독으로 막는지", "예외로 죽어도 가드가 finally로 풀리는지"를
 * CountDownLatch로 결정론적으로 검증한다(폴링·sleep 없이). manual-trigger-enabled 기본값은 false(운영
 * 공격면 최소화)라 이 컨트롤러 자체를 검증하려면 프로퍼티를 켜야 한다 — 게이트가 꺼졌을 때의 동작(404)은
 * {@link SweepControllerDisabledTest}가 별도 컨텍스트로 검증한다.
 */
@SpringBootTest(properties = "monitoring.sweep.manual-trigger-enabled=true")
class SweepControllerTest {

	/**
	 * 테스트 전용 컨트롤 지점 — runWithId(protected, DailySweepJob 참고) 진입 시 entered를 풀고
	 * release가 열릴 때까지 블로킹해 "실행 중" 구간을 임의로 늘린다. throwOnRelease면 실제 스윕
	 * 대신 예외를 던져 가드 finally 해제(누수 회귀 가드)를 검증한다.
	 */
	static class ControllableSweepJob extends DailySweepJob {
		volatile CountDownLatch entered;
		volatile CountDownLatch release;
		volatile CountDownLatch finished;
		volatile boolean throwOnRelease;

		ControllableSweepJob(TargetRepository targets, CollectService collect, AlarmRecorder alarms,
				SweepRunRepository sweepRuns) {
			super(targets, collect, alarms, sweepRuns, null, 0, Duration.ZERO);
			reset();
		}

		/** 다음 시나리오를 위해 래치를 새로 판다 — CountDownLatch는 한 번 열리면 재사용할 수 없다. */
		void reset() {
			entered = new CountDownLatch(1);
			release = new CountDownLatch(1);
			finished = new CountDownLatch(1);
			throwOnRelease = false;
		}

		@Override
		protected void runWithId(Long runId) {
			entered.countDown();
			awaitQuietly(release);
			try {
				if (throwOnRelease) {
					throw new IllegalStateException("테스트 유도 실패 — 가드 누수 회귀 검증용");
				}
				super.runWithId(runId);   // target이 없어 실제 수집 콜 없이 sweep_run만 완료 기록된다.
			} finally {
				finished.countDown();
			}
		}

		private static void awaitQuietly(CountDownLatch latch) {
			try {
				if (!latch.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("테스트 타임아웃 — release 신호가 오지 않았다");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@TestConfiguration
	static class Fakes {
		@Bean
		@Primary
		ControllableSweepJob controllableSweepJob(TargetRepository targets, CollectService collect,
				AlarmRecorder alarms, SweepRunRepository sweepRuns) {
			return new ControllableSweepJob(targets, collect, alarms, sweepRuns);
		}
	}

	@DynamicPropertySource
	static void dbProps(DynamicPropertyRegistry r) {
		var pg = TestDb.container();
		r.add("spring.datasource.url", pg::getJdbcUrl);
		r.add("spring.datasource.username", pg::getUsername);
		r.add("spring.datasource.password", pg::getPassword);
	}

	@Autowired WebApplicationContext ctx;
	@Autowired JdbcTemplate db;
	@Autowired ControllableSweepJob job;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
		db.update("DELETE FROM sweep_run");
		job.reset();
	}

	@Test
	void POST_sweeps는_202이고_runId_startedAt을_돌려주며_비동기로_1회_실행된다() throws Exception {
		mvc.perform(post("/api/sweeps"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.runId").isNumber())
				.andExpect(jsonPath("$.startedAt").exists());

		// 컨트롤러 스레드가 아니라 별도 스레드에서 실행됐다는 증거 — 여기서 걸리면 동기 실행이라는 뜻.
		assertThat(job.entered.await(5, TimeUnit.SECONDS)).isTrue();
		job.release.countDown();
		assertThat(job.finished.await(5, TimeUnit.SECONDS)).isTrue();

		// run()과 동일한 sweep_run 격리 계약 — 정상 완주는 ok=true 1행.
		assertThat(db.queryForObject("SELECT count(*) FROM sweep_run", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("SELECT ok FROM sweep_run", Boolean.class)).isTrue();
	}

	@Test
	void 실행_중일_때_두번째_POST는_409_SWEEP_ALREADY_RUNNING() throws Exception {
		mvc.perform(post("/api/sweeps")).andExpect(status().isAccepted());
		assertThat(job.entered.await(5, TimeUnit.SECONDS)).isTrue();

		mvc.perform(post("/api/sweeps"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SWEEP_ALREADY_RUNNING"));

		job.release.countDown();
		assertThat(job.finished.await(5, TimeUnit.SECONDS)).isTrue();
	}

	/**
	 * 가드 누수 회귀 가드 — 스윕이 (재시도 대기 인터럽트 등으로) 예외로 죽어도 SweepCommandService의
	 * finally가 가드를 풀어야 한다. 여기서 풀리지 않으면 다음 트리거가 영원히 409만 받는다.
	 *
	 * <p>job.finished는 ControllableSweepJob.runWithId 자신의 finally에서 풀린다 — 그런데 실제 가드
	 * 해제(guard.release())는 그 한 단계 바깥, SweepCommandService의 executor 람다 finally다.
	 * 예외가 runWithId → 그 람다로 전파되는 사이 아주 짧은 창이 있어, finished만 보고 바로 재시도하면
	 * 드물게 409가 뜬다 — 그래서 가드가 실제로 풀릴 때까지 짧게 재시도한다(최대 2초).
	 */
	@Test
	void 스윕이_예외로_죽어도_가드는_풀리고_다음_트리거는_다시_202를_받는다() throws Exception {
		job.throwOnRelease = true;

		mvc.perform(post("/api/sweeps")).andExpect(status().isAccepted());
		assertThat(job.entered.await(5, TimeUnit.SECONDS)).isTrue();
		job.release.countDown();
		assertThat(job.finished.await(5, TimeUnit.SECONDS)).isTrue();

		job.reset();   // 예외 경로에서 super.runWithId를 안 탔으니 sweep_run 완료 기록도 없다 — 다음 트리거로 새 라운드
		int status = retryPostUntilNotConflict();
		assertThat(status).isEqualTo(202);
		assertThat(job.entered.await(5, TimeUnit.SECONDS)).isTrue();
		job.release.countDown();
		assertThat(job.finished.await(5, TimeUnit.SECONDS)).isTrue();
	}

	/** guard.release()가 예외 경로에서 완전히 반영될 때까지 짧게 재시도 — 실행 스레드 종료와의 경합 흡수. */
	private int retryPostUntilNotConflict() throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		int status;
		do {
			status = mvc.perform(post("/api/sweeps")).andReturn().getResponse().getStatus();
		} while (status == 409 && System.nanoTime() < deadline);
		return status;
	}

	@Test
	void GET_latest는_이력이_없으면_필드가_비고_404가_아니다() throws Exception {
		mvc.perform(get("/api/sweeps/latest"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.runId").value(nullValue()))
				.andExpect(jsonPath("$.running").value(false));
	}

	@Test
	void GET_latest는_완료된_스윕의_상태를_돌려준다() throws Exception {
		mvc.perform(post("/api/sweeps")).andExpect(status().isAccepted());
		assertThat(job.entered.await(5, TimeUnit.SECONDS)).isTrue();
		job.release.countDown();
		assertThat(job.finished.await(5, TimeUnit.SECONDS)).isTrue();

		mvc.perform(get("/api/sweeps/latest"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.runId").isNumber())
				.andExpect(jsonPath("$.completedAt").exists())
				.andExpect(jsonPath("$.ok").value(true))
				.andExpect(jsonPath("$.running").value(false));
	}

	@Test
	void GET_latest는_실행_중이면_running_true다() throws Exception {
		mvc.perform(post("/api/sweeps")).andExpect(status().isAccepted());
		assertThat(job.entered.await(5, TimeUnit.SECONDS)).isTrue();

		mvc.perform(get("/api/sweeps/latest"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.completedAt").value(nullValue()))
				.andExpect(jsonPath("$.running").value(true));

		job.release.countDown();
		assertThat(job.finished.await(5, TimeUnit.SECONDS)).isTrue();
	}
}
