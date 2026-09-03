package com.celfit.monitoring.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.store.AppSettingRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 해시태그 제안 설정 TTL 캐시 — {@code IgSourceSettings}와 같은 관용구(짧은 TTL·이상값 안전측·
 * 조회 실패 시 직전 캐시 유지)를 세 키(min-posts·stoplist·ai-enabled)에 대해 고정한다.
 */
class BrandHashtagSeedSettingsTest {

	/** app_setting 스텁 — 조회 횟수를 세서 TTL 캐시 적중을 관측한다. */
	private static final class StubSettings extends AppSettingRepository {
		final Map<String, String> values = new HashMap<>();
		int reads;
		boolean failing;

		StubSettings() {
			super(null);
		}

		@Override
		public Optional<String> find(String key) {
			if (failing) {
				throw new IllegalStateException("DB 장애 주입");
			}
			reads++;
			return Optional.ofNullable(values.get(key));
		}
	}

	/** 수동으로 흘릴 수 있는 시계 — TTL 만료를 결정적으로 재현한다. */
	private static final class MutableClock extends Clock {
		Instant now = Instant.parse("2026-09-03T00:00:00Z");

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final StubSettings store = new StubSettings();
	private final MutableClock clock = new MutableClock();

	private BrandHashtagSeedSettings settings() {
		return new BrandHashtagSeedSettings(store, clock, Duration.ofSeconds(5));
	}

	@Test
	void 키가_없으면_기본값이다() {
		var s = settings();

		assertThat(s.minPosts()).isEqualTo(7);
		assertThat(s.aiEnabled()).isTrue();
		assertThat(s.stoplist()).contains("광고", "협찬", "sponsored");
	}

	@Test
	void 설정된_값을_읽는다() {
		store.values.put("brand.hashtag-seed.min-posts", "3");
		store.values.put("brand.hashtag-seed.ai-enabled", "false");
		store.values.put("brand.hashtag-seed.stoplist", "가,나");

		var s = settings();

		assertThat(s.minPosts()).isEqualTo(3);
		assertThat(s.aiEnabled()).isFalse();
		assertThat(s.stoplist()).containsExactlyInAnyOrder("가", "나");
	}

	@Test
	void 숫자가_아닌_min_posts는_기본값으로_접힌다() {
		store.values.put("brand.hashtag-seed.min-posts", "일곱");

		assertThat(settings().minPosts()).isEqualTo(7);
	}

	@Test
	void 영이하_min_posts는_기본값으로_접힌다() {
		store.values.put("brand.hashtag-seed.min-posts", "0");

		assertThat(settings().minPosts()).isEqualTo(7);

		store.values.put("brand.hashtag-seed.min-posts", "-5");

		assertThat(settings().minPosts()).isEqualTo(7);
	}

	@Test
	void stoplist는_트림_소문자_빈토큰제거로_파싱된다() {
		store.values.put("brand.hashtag-seed.stoplist", " AD , ,협찬 ,");

		assertThat(settings().stoplist()).containsExactlyInAnyOrder("ad", "협찬");
	}

	@Test
	void 빈_stoplist는_빈_집합이다() {
		store.values.put("brand.hashtag-seed.stoplist", "  ");

		assertThat(settings().stoplist()).isEmpty();
	}

	@Test
	void TTL_안에서는_재조회하지_않는다() {
		var s = settings();
		s.minPosts();
		int afterFirst = store.reads;

		s.minPosts();
		s.aiEnabled();
		s.stoplist();

		assertThat(store.reads).isEqualTo(afterFirst);
	}

	@Test
	void TTL이_지나면_재조회한다() {
		var s = settings();
		s.minPosts();
		int afterFirst = store.reads;
		store.values.put("brand.hashtag-seed.min-posts", "3");

		clock.now = clock.now.plusSeconds(6);

		assertThat(s.minPosts()).isEqualTo(3);
		assertThat(store.reads).isGreaterThan(afterFirst);
	}

	@Test
	void 조회_실패는_직전_캐시를_유지한다() {
		store.values.put("brand.hashtag-seed.min-posts", "3");
		var s = settings();
		assertThat(s.minPosts()).isEqualTo(3);

		store.failing = true;
		clock.now = clock.now.plusSeconds(6);

		assertThat(s.minPosts()).isEqualTo(3);
	}

	@Test
	void 캐시가_없는데_조회에_실패하면_기본값이다() {
		store.failing = true;

		assertThat(settings().minPosts()).isEqualTo(7);
		assertThat(settings().aiEnabled()).isTrue();
	}

	/**
	 * Flyway 시드값이 클래스 기본값 상수와 일치하는지 검증(실 Testcontainers Postgres) —
	 * {@code AppSettingRepositoryTest.시드된_토글_기준값을_조회한다} /
	 * {@code IgSourceSettingsTest.commentDocId_는_마이그레이션_시드값이다}와 같은 패턴.
	 */
	@Test
	void 마이그레이션_시드값은_클래스_기본값과_같다() {
		var ds = TestDb.dataSource(TestDb.container());
		var db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		var repo = new AppSettingRepository(db);

		assertThat(repo.find("brand.hashtag-seed.min-posts")).isEqualTo(Optional.of("7"));
		assertThat(repo.find("brand.hashtag-seed.ai-enabled")).isEqualTo(Optional.of("true"));
		assertThat(repo.find("brand.hashtag-seed.stoplist"))
				.isEqualTo(Optional.of(BrandHashtagSeedSettings.DEFAULT_STOPLIST));
	}
}
