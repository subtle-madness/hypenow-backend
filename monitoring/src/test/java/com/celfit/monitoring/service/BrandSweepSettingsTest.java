package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.store.AppSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 스윕 병렬도 런타임 토글 — app_setting 값의 파싱·클램프·TTL 캐시·DB 장애 fail-safe를 검증한다
 * (IgSourceSettings와 같은 관용구, DB 없이 스텁 리포지토리로).
 */
class BrandSweepSettingsTest {

	private static final Duration TTL = Duration.ofSeconds(5);

	/** 테스트에서 시간을 임의로 진행시키는 Clock 스텁(IgSourceSettingsTest SteppingClock과 동형). */
	private static final class SteppingClock extends Clock {

		private Instant now = Instant.parse("2026-09-03T00:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	/** DB 없는 스텁 — 값 주입·조회 횟수 관측·장애 주입. */
	private static final class StubSettings extends AppSettingRepository {

		final Map<String, String> values = new HashMap<>();
		int calls;
		RuntimeException failure;

		StubSettings() {
			super(null);
		}

		@Override
		public Optional<String> find(String key) {
			calls++;
			if (failure != null) {
				throw failure;
			}
			return Optional.ofNullable(values.get(key));
		}
	}

	private final StubSettings repo = new StubSettings();
	private final SteppingClock clock = new SteppingClock();

	/** 코드 기본값 = 시드 기본값(브랜드 3, 2단계 8), 하드 상한(풀 크기) = 같은 값. */
	private BrandSweepSettings settings() {
		return new BrandSweepSettings(repo, 3, 8, clock, TTL);
	}

	@Test
	void 키가_없으면_코드_기본값을_쓴다() {
		assertThat(settings().brandConcurrency()).isEqualTo(3);
		assertThat(settings().unenumeratedConcurrency()).isEqualTo(8);
	}

	@Test
	void 런타임_값이_있으면_그_값을_쓴다() {
		repo.values.put("brand-sweep.brand-concurrency", "2");
		repo.values.put("brand-sweep.unenumerated-concurrency", "4");

		assertThat(settings().brandConcurrency()).isEqualTo(2);
		assertThat(settings().unenumeratedConcurrency()).isEqualTo(4);
	}

	/** 킬스위치 — 1이면 호출부가 현행 직렬 경로를 그대로 탄다. */
	@Test
	void 값_1은_그대로_직렬_복원값으로_읽힌다() {
		repo.values.put("brand-sweep.brand-concurrency", "1");
		repo.values.put("brand-sweep.unenumerated-concurrency", "1");

		assertThat(settings().brandConcurrency()).isEqualTo(1);
		assertThat(settings().unenumeratedConcurrency()).isEqualTo(1);
	}

	/** 상한(전용 풀 크기)을 넘는 값은 클램프 — 풀보다 큰 병렬도는 어차피 실현되지 않는다. */
	@Test
	void 풀_크기를_넘는_값은_풀_크기로_클램프된다() {
		repo.values.put("brand-sweep.brand-concurrency", "99");
		repo.values.put("brand-sweep.unenumerated-concurrency", "99");

		assertThat(settings().brandConcurrency()).isEqualTo(3);
		assertThat(settings().unenumeratedConcurrency()).isEqualTo(8);
	}

	@Test
	void 영이나_음수는_1로_클램프된다() {
		repo.values.put("brand-sweep.brand-concurrency", "0");
		repo.values.put("brand-sweep.unenumerated-concurrency", "-5");

		assertThat(settings().brandConcurrency()).isEqualTo(1);
		assertThat(settings().unenumeratedConcurrency()).isEqualTo(1);
	}

	@Test
	void 숫자가_아닌_값은_기본값으로_폴백한다() {
		repo.values.put("brand-sweep.brand-concurrency", "많이");

		assertThat(settings().brandConcurrency()).isEqualTo(3);
	}

	@Test
	void TTL_안에서는_DB를_다시_읽지_않고_만료_후_새_값이_반영된다() {
		BrandSweepSettings s = settings();
		assertThat(s.brandConcurrency()).isEqualTo(3);
		int afterFirst = repo.calls;

		s.brandConcurrency();
		s.unenumeratedConcurrency();
		assertThat(repo.calls).isEqualTo(afterFirst);   // 캐시 적중 — DB 왕복 없음

		repo.values.put("brand-sweep.brand-concurrency", "1");
		assertThat(s.brandConcurrency()).isEqualTo(3);  // 아직 TTL 안 — 옛 값

		clock.advance(TTL.plusSeconds(1));
		assertThat(s.brandConcurrency()).isEqualTo(1);  // 재배포 없이 반영
		assertThat(repo.calls).isGreaterThan(afterFirst);
	}

	@Test
	void DB_조회_실패는_직전_값을_유지한다() {
		repo.values.put("brand-sweep.brand-concurrency", "2");
		BrandSweepSettings s = settings();
		assertThat(s.brandConcurrency()).isEqualTo(2);

		repo.failure = new DataAccessResourceFailureException("DB 다운");
		clock.advance(TTL.plusSeconds(1));

		assertThat(s.brandConcurrency()).isEqualTo(2);
	}

	@Test
	void 캐시가_없는_상태의_DB_조회_실패는_코드_기본값으로_fail_safe한다() {
		repo.failure = new DataAccessResourceFailureException("부팅 직후 DB 다운");

		assertThat(settings().brandConcurrency()).isEqualTo(3);
		assertThat(settings().unenumeratedConcurrency()).isEqualTo(8);
	}
}
