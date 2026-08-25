package com.celfit.monitoring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.monitoring.image.AuthorImageBackfillJob;
import com.celfit.monitoring.service.AuthorImageBackfillGuard;
import com.celfit.monitoring.testsupport.TestDb;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 이미지 백필 수동 트리거 API — POST /api/author-image-backfill(비동기 시작). 실제
 * {@link AuthorImageBackfillJob} 대신 {@link ControllableJob}(run을 오버라이드해 타이밍을 통제하는
 * 서브클래스, 의존성은 전부 null — 오버라이드로 실제 DB·Hiker에 닿지 않는다)을 @Primary로 꽂아
 * "비동기로 실행되는지", "동시 실행 가드가 막는지"를 CountDownLatch로 결정론적으로 검증한다
 * (SweepControllerTest와 동형 — "완료" 신호를 job이 아니라 {@link ControllableGuard#released}로
 * 잡는 이유도 동일: SweepCommandService.start()의 executor 람다 finally가 한 프레임 더 있다).
 * 게이트가 꺼졌을 때의 동작(404)은 {@link AuthorImageBackfillControllerDisabledTest}가 검증한다.
 */
@SpringBootTest(properties = "monitoring.image.backfill-trigger-enabled=true")
class AuthorImageBackfillControllerTest {

	static class ControllableJob extends AuthorImageBackfillJob {
		volatile CountDownLatch entered;
		volatile CountDownLatch release;

		ControllableJob() {
			super(null, null, null, null, null);
			reset();
		}

		void reset() {
			entered = new CountDownLatch(1);
			release = new CountDownLatch(1);
		}

		@Override
		public Result run(int limit) {
			entered.countDown();
			awaitQuietly(release);
			return new Result(new PhaseAResult(0, 0, 0), new PhaseBResult(0, 0, 0, 0));
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

	static class ControllableGuard extends AuthorImageBackfillGuard {
		volatile CountDownLatch released = new CountDownLatch(1);

		@Override
		public void release() {
			super.release();
			released.countDown();
		}

		void reset() {
			released = new CountDownLatch(1);
		}
	}

	@TestConfiguration
	static class Fakes {
		@Bean
		@Primary
		ControllableJob controllableJob() {
			return new ControllableJob();
		}

		@Bean
		@Primary
		ControllableGuard controllableGuard() {
			return new ControllableGuard();
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
	@Autowired ControllableJob job;
	@Autowired ControllableGuard guard;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
		job.reset();
		guard.reset();
	}

	@Test
	void POST는_202이고_limit_startedAt을_돌려주며_비동기로_1회_실행된다() throws Exception {
		mvc.perform(post("/api/author-image-backfill?limit=100"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.limit").value(100))
				.andExpect(jsonPath("$.startedAt").exists());

		// 컨트롤러 스레드가 아니라 별도 스레드에서 실행됐다는 증거 — 여기서 걸리면 동기 실행이라는 뜻.
		assertEntered();
		job.release.countDown();
		assertReleased();
	}

	@Test
	void limit_파라미터가_없으면_400() throws Exception {
		mvc.perform(post("/api/author-image-backfill")).andExpect(status().isBadRequest());
	}

	@Test
	void 실행_중일_때_두번째_POST는_409_AUTHOR_IMAGE_BACKFILL_ALREADY_RUNNING() throws Exception {
		mvc.perform(post("/api/author-image-backfill?limit=1")).andExpect(status().isAccepted());
		assertEntered();

		mvc.perform(post("/api/author-image-backfill?limit=1"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("AUTHOR_IMAGE_BACKFILL_ALREADY_RUNNING"));

		job.release.countDown();
		assertReleased();
	}

	private void assertEntered() throws InterruptedException {
		assertThat(job.entered.await(5, TimeUnit.SECONDS)).isTrue();
	}

	private void assertReleased() throws InterruptedException {
		assertThat(guard.released.await(5, TimeUnit.SECONDS)).isTrue();
	}
}
