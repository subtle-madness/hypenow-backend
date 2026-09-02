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
 * 재시작 없는 반영·DB 장애 fail-safe·프록시 미설정 게이트를 검증한다.
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
		// 레지덴셜 프록시가 설정된 상태 — F5(프록시 미설정 게이트)는 별도 테스트에서 null로 검증.
		proxyProps = new InstagramProxyProperties("http://residential.proxy:8080", null,
				Duration.ofSeconds(15), false, "ENV_DOC_ID", "ENV_FRIENDLY_NAME");
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

	// ── F5: 프록시 미설정이면 자체크롤 금지 ──────────────────────────────

	@Test
	void 프록시_URL이_비어있으면_self_enabled여도_selfEnabled는_false다() {
		InstagramProxyProperties noProxy = new InstagramProxyProperties(null, null,
				Duration.ofSeconds(15), false, "ENV_DOC_ID", "ENV_FRIENDLY_NAME");
		repo.upsert("ig-source.self-enabled", "true");
		IgSourceSettings settings = new IgSourceSettings(repo, noProxy, clock, TTL);

		assertThat(settings.selfEnabled()).isFalse();
	}

	// ── 경로별(표면별) 자체크롤 토글 — 부분 개통("프로필만 빼고 켜기") 지원 ───────
	// 토큰 = FailoverInstagramSource.route()가 넘기는 path 문자열(=metric path 태그)과 동일:
	// fetchProfile · fetchRecentPosts · fetchPost · fetchComments. 별칭 매핑 없이 그대로 재사용한다.

	/** 마이그레이션 시드값 — 전체 4경로(마스터 토글 self-enabled가 여전히 off라 행동 변화 0). */
	@Test
	void self_paths_시드값은_전체_4경로다() {
		repo.upsert("ig-source.self-enabled", "true");
		IgSourceSettings settings = settings();

		assertThat(settings.selfEnabledForPath("fetchProfile")).isTrue();
		assertThat(settings.selfEnabledForPath("fetchRecentPosts")).isTrue();
		assertThat(settings.selfEnabledForPath("fetchPost")).isTrue();
		assertThat(settings.selfEnabledForPath("fetchComments")).isTrue();
	}

	@Test
	void self_paths에서_특정_경로를_빼면_그_경로만_false다() {
		repo.upsert("ig-source.self-enabled", "true");
		repo.upsert("ig-source.self-paths", "fetchRecentPosts,fetchPost,fetchComments");
		IgSourceSettings settings = settings();

		assertThat(settings.selfEnabledForPath("fetchProfile")).isFalse();
		assertThat(settings.selfEnabledForPath("fetchPost")).isTrue();
		assertThat(settings.selfEnabledForPath("fetchComments")).isTrue();
	}

	/** 빈 값 = 전부 비활성이 안전측. */
	@Test
	void self_paths가_빈_문자열이면_전부_비활성이다() {
		repo.upsert("ig-source.self-enabled", "true");
		repo.upsert("ig-source.self-paths", "");
		IgSourceSettings settings = settings();

		assertThat(settings.selfEnabledForPath("fetchPost")).isFalse();
		assertThat(settings.selfEnabledForPath("fetchProfile")).isFalse();
	}

	/** 키 자체가 없어도(행 삭제) 빈 값과 동일하게 안전측으로 처리한다. */
	@Test
	void self_paths_키가_없으면_전부_비활성이다() {
		repo.upsert("ig-source.self-enabled", "true");
		db.update("DELETE FROM app_setting WHERE key = 'ig-source.self-paths'");
		IgSourceSettings settings = settings();

		assertThat(settings.selfEnabledForPath("fetchPost")).isFalse();
	}

	/** 공백·연속 콤마로 생기는 빈 토큰은 트림 후 걸러진다 — 알 수 없는 토큰과 섞여도 나머지는 정상 판정. */
	@Test
	void self_paths_공백과_빈_토큰은_무시된다() {
		repo.upsert("ig-source.self-enabled", "true");
		repo.upsert("ig-source.self-paths", " fetchPost , , fetchComments ,unknownToken");
		IgSourceSettings settings = settings();

		assertThat(settings.selfEnabledForPath("fetchPost")).isTrue();
		assertThat(settings.selfEnabledForPath("fetchComments")).isTrue();
		// 목록에 없는 경로는 여전히 false — 알 수 없는 토큰이 섞여 있어도 다른 경로를 켜지 않는다.
		assertThat(settings.selfEnabledForPath("fetchProfile")).isFalse();
	}

	@Test
	void 전역_force_hiker면_self_paths에_있어도_selfEnabledForPath는_false다() {
		repo.upsert("ig-source.self-enabled", "true");
		repo.upsert("ig-source.force-hiker", "true");
		IgSourceSettings settings = settings();

		assertThat(settings.selfEnabledForPath("fetchPost")).isFalse();
	}

	@Test
	void self_enabled_자체가_false면_self_paths와_무관하게_전부_false다() {
		// self-enabled는 시드 기본값(false) 그대로 — self-paths만 전체로 두어도 마스터 토글이 이긴다.
		IgSourceSettings settings = settings();

		assertThat(settings.selfEnabledForPath("fetchPost")).isFalse();
	}

	@Test
	void self_paths_변경도_TTL_경유로_반영된다() {
		repo.upsert("ig-source.self-enabled", "true");
		IgSourceSettings settings = settings();
		assertThat(settings.selfEnabledForPath("fetchProfile")).isTrue();

		repo.upsert("ig-source.self-paths", "fetchPost");
		// TTL 내 — 아직 캐시가 살아있어 반영되지 않는다.
		assertThat(settings.selfEnabledForPath("fetchProfile")).isTrue();

		clock.advance(TTL.plusSeconds(1));
		assertThat(settings.selfEnabledForPath("fetchProfile")).isFalse();
		assertThat(settings.selfEnabledForPath("fetchPost")).isTrue();
	}

	@Test
	void DB_예외이고_직전_캐시가_없으면_selfEnabledForPath도_false_안전측이다() {
		IgSourceSettings settings = new IgSourceSettings(new AlwaysFailingRepo(), proxyProps, clock, TTL);

		assertThat(settings.selfEnabledForPath("fetchPost")).isFalse();
	}

	// ── 사용자 트리거 비동기 흐름 도입 시점 토글(ig-source.self-user-triggered, 2026-09) ─────
	// 새벽 스케줄 트리거(instagramSource 빈, self-enabled만으로 개통)와 사용자 트리거 비동기
	// (userTriggeredInstagramSource 빈)를 분리하는 별도 축 — 전역 게이트(force-hiker·self-enabled·
	// proxy)와 무관하게 이 값 하나로 판정한다(실제 self 사용 가능 여부는 라우팅된 뒤 selfEnabled가
	// 다시 게이트한다).

	/** 마이그레이션 시드값 — false(사용자 트리거는 계속 Hiker 1순위, 행동 변화 0). */
	@Test
	void 시드_기본값에서_selfUserTriggered는_false다() {
		assertThat(settings().selfUserTriggered()).isFalse();
	}

	@Test
	void self_user_triggered를_true로_올리면_재시작_없이_반영된다() {
		repo.upsert("ig-source.self-user-triggered", "true");
		assertThat(settings().selfUserTriggered()).isTrue();
	}

	/** 전역 게이트(self-enabled)와 무관한 별도 축 — self-enabled가 꺼져 있어도 이 값만으로 판정한다. */
	@Test
	void self_user_triggered는_self_enabled와_무관하게_독립_판정된다() {
		repo.upsert("ig-source.self-user-triggered", "true");
		IgSourceSettings settings = settings();

		assertThat(settings.selfUserTriggered()).isTrue();
		assertThat(settings.selfEnabled()).isFalse();   // self-enabled는 시드 기본값(false) 그대로
	}

	@Test
	void self_user_triggered_변경도_TTL_경유로_반영된다() {
		IgSourceSettings settings = settings();
		assertThat(settings.selfUserTriggered()).isFalse();

		repo.upsert("ig-source.self-user-triggered", "true");
		// TTL 내 — 아직 캐시가 살아있어 반영되지 않는다.
		assertThat(settings.selfUserTriggered()).isFalse();

		clock.advance(TTL.plusSeconds(1));
		assertThat(settings.selfUserTriggered()).isTrue();
	}

	@Test
	void DB_예외이고_직전_캐시가_없으면_selfUserTriggered도_false_안전측이다() {
		IgSourceSettings settings = new IgSourceSettings(new AlwaysFailingRepo(), proxyProps, clock, TTL);

		assertThat(settings.selfUserTriggered()).isFalse();
	}

	@Test
	void 프록시_URL이_비어있으면_self_paths에_있어도_selfEnabledForPath는_false다() {
		InstagramProxyProperties noProxy = new InstagramProxyProperties(null, null,
				Duration.ofSeconds(15), false, "ENV_DOC_ID", "ENV_FRIENDLY_NAME");
		repo.upsert("ig-source.self-enabled", "true");
		IgSourceSettings settings = new IgSourceSettings(repo, noProxy, clock, TTL);

		assertThat(settings.selfEnabledForPath("fetchPost")).isFalse();
	}
}
