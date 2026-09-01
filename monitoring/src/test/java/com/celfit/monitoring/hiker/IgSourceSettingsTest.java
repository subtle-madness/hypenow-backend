package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.store.AppSettingRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 자체크롤 런타임 토글·킬스위치 판정 — app_setting 시드 기준값(전량 off)과 TTL 캐시(5초) 기준
 * 재시작 없는 반영·DB 장애 fail-safe를 검증한다.
 */
class IgSourceSettingsTest {

	private static final Duration TTL = Duration.ofSeconds(5);

	/** 테스트에서 시간을 임의로 진행시키는 Clock 스텁(RateLimiterTest SteppingClock과 동형). */
	private static final class SteppingClock extends Clock {

		private Instant now = Instant.parse("2026-09-01T00:00:00Z");

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

	/** find() 호출 횟수를 세는 래퍼 — 캐시가 실제로 DB 왕복을 줄이는지 검증용. */
	private static final class CountingRepo extends AppSettingRepository {

		private final AppSettingRepository delegate;
		int calls = 0;

		CountingRepo(AppSettingRepository delegate) {
			super(null);
			this.delegate = delegate;
		}

		@Override
		public Optional<String> find(String key) {
			calls++;
			return delegate.find(key);
		}
	}

	/** N번째 호출부터 DB 장애처럼 던지는 래퍼 — 성공 캐시 적재 후 장애 전환을 재현한다. */
	private static final class FlakyRepo extends AppSettingRepository {

		private final AppSettingRepository delegate;
		private volatile boolean failing = false;

		FlakyRepo(AppSettingRepository delegate) {
			super(null);
			this.delegate = delegate;
		}

		void startFailing() {
			failing = true;
		}

		@Override
		public Optional<String> find(String key) {
			if (failing) {
				throw new DataAccessResourceFailureException("DB 다운(시뮬레이션)");
			}
			return delegate.find(key);
		}
	}

	private static final class AlwaysFailingRepo extends AppSettingRepository {

		AlwaysFailingRepo() {
			super(null);
		}

		@Override
		public Optional<String> find(String key) {
			throw new DataAccessResourceFailureException("DB 다운(시뮬레이션)");
		}
	}

	JdbcTemplate db;
	AppSettingRepository repo;
	InstagramProxyProperties proxyProps;
	SteppingClock clock;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		repo = new AppSettingRepository(db);
		proxyProps = new InstagramProxyProperties(
				null, null, Duration.ofSeconds(15), false, "ENV_DOC_ID", "ENV_FRIENDLY_NAME");
		clock = new SteppingClock();
	}

	private IgSourceSettings settings() {
		return new IgSourceSettings(repo, proxyProps, clock, TTL);
	}

	/** 마이그레이션 시드 기준값(self-enabled=false) — 개통 전 행동 변화 0. */
	@Test
	void 시드_기본값에서_selfEnabled는_false다() {
		assertThat(settings().selfEnabled()).isFalse();
	}

	@Test
	void self_enabled를_true로_올리면_재시작_없이_selfEnabled가_true다() {
		repo.upsert("ig-source.self-enabled", "true");
		assertThat(settings().selfEnabled()).isTrue();
	}

	@Test
	void 킬스위치_force_hiker가_true면_self_enabled가_true라도_false다() {
		repo.upsert("ig-source.self-enabled", "true");
		repo.upsert("ig-source.force-hiker", "true");
		assertThat(settings().selfEnabled()).isFalse();
	}

	@Test
	void profileSurface_기본은_wpi고_TTL_경과_후_upsert가_og로_반영된다() {
		IgSourceSettings settings = settings();
		assertThat(settings.profileSurface()).isEqualTo("wpi");
		repo.upsert("ig-source.profile-surface", "og");
		clock.advance(TTL.plusSeconds(1));
		assertThat(settings.profileSurface()).isEqualTo("og");
	}

	/** 마이그레이션 시드값(V20260901~ig_source_comment_doc_id) — env 폴백보다 app_setting이 우선. */
	@Test
	void commentDocId_는_마이그레이션_시드값이다() {
		IgSourceSettings settings = settings();
		assertThat(settings.commentDocId()).isEqualTo("27659279553772821");
		assertThat(settings.commentFriendlyName())
				.isEqualTo("PolarisLoggedOutDesktopWWWPostCommentsPaginationQuery");
	}

	@Test
	void commentDocId_upsert하면_재시작_없이_반영된다() {
		repo.upsert("ig-source.comment-doc-id", "NEW_DOC_ID");
		repo.upsert("ig-source.comment-friendly-name", "NewFriendlyName");
		IgSourceSettings settings = settings();
		assertThat(settings.commentDocId()).isEqualTo("NEW_DOC_ID");
		assertThat(settings.commentFriendlyName()).isEqualTo("NewFriendlyName");
	}

	@Test
	void commentDocId_app_setting이_비어있으면_env_폴백() {
		db.update("DELETE FROM app_setting WHERE key IN "
				+ "('ig-source.comment-doc-id', 'ig-source.comment-friendly-name')");
		IgSourceSettings settings = settings();
		assertThat(settings.commentDocId()).isEqualTo("ENV_DOC_ID");
		assertThat(settings.commentFriendlyName()).isEqualTo("ENV_FRIENDLY_NAME");
	}

	// ── F4: TTL 캐시 ─────────────────────────────────────────────────────

	@Test
	void TTL_내_재조회는_DB를_안_친다() {
		CountingRepo counting = new CountingRepo(repo);
		IgSourceSettings settings = new IgSourceSettings(counting, proxyProps, clock, TTL);

		settings.selfEnabled();
		int callsAfterFirstLoad = counting.calls;
		assertThat(callsAfterFirstLoad).isGreaterThan(0);

		settings.selfEnabled();
		settings.profileSurface();
		settings.commentDocId();

		assertThat(counting.calls).isEqualTo(callsAfterFirstLoad);
	}

	@Test
	void TTL_경과_후_재조회한다() {
		CountingRepo counting = new CountingRepo(repo);
		IgSourceSettings settings = new IgSourceSettings(counting, proxyProps, clock, TTL);

		settings.selfEnabled();
		int callsAfterFirstLoad = counting.calls;

		clock.advance(TTL.plusSeconds(1));
		settings.selfEnabled();

		assertThat(counting.calls).isGreaterThan(callsAfterFirstLoad);
	}

	@Test
	void DB_예외이고_직전_캐시가_없으면_selfEnabled는_false_안전측이다() {
		IgSourceSettings settings = new IgSourceSettings(new AlwaysFailingRepo(), proxyProps, clock, TTL);

		assertThat(settings.selfEnabled()).isFalse();
		assertThat(settings.profileSurface()).isEqualTo("wpi");
		assertThat(settings.commentDocId()).isEqualTo("ENV_DOC_ID");
		assertThat(settings.commentFriendlyName()).isEqualTo("ENV_FRIENDLY_NAME");
	}

	@Test
	void DB_예외이고_직전_캐시가_있으면_직전_값을_유지한다() {
		repo.upsert("ig-source.self-enabled", "true");
		FlakyRepo flaky = new FlakyRepo(repo);
		IgSourceSettings settings = new IgSourceSettings(flaky, proxyProps, clock, TTL);

		// 1) TTL 내 정상 조회 — 캐시에 self-enabled=true 적재.
		assertThat(settings.selfEnabled()).isTrue();

		// 2) TTL 경과 + DB 장애 전환 — 재조회는 실패하지만 직전 캐시값을 그대로 쓴다.
		flaky.startFailing();
		clock.advance(TTL.plusSeconds(1));
		assertThat(settings.selfEnabled()).isTrue();
	}

	@Test
	void force_hiker_갱신이_TTL_후_반영된다() {
		repo.upsert("ig-source.self-enabled", "true");
		IgSourceSettings settings = settings();

		assertThat(settings.selfEnabled()).isTrue();

		// TTL 내 upsert — 아직 캐시가 살아있어 반영되지 않는다(킬스위치 지연 허용, 08-31 F4).
		repo.upsert("ig-source.force-hiker", "true");
		assertThat(settings.selfEnabled()).isTrue();

		// TTL 경과 후에는 반영된다.
		clock.advance(TTL.plusSeconds(1));
		assertThat(settings.selfEnabled()).isFalse();
	}
}
