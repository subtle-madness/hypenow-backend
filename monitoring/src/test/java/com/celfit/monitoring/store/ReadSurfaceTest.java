package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.testsupport.TestDb;
import java.sql.Date;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 조회 표면(V2) — was가 SELECT할 뷰 2종과 읽기 전용 권한 경계. */
class ReadSurfaceTest {

	JdbcTemplate db;
	JdbcTemplate wasReader;

	@BeforeEach
	void setUp() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		wasReader = new JdbcTemplate(TestDb.wasReaderDataSource(pg));
		seed();
	}

	/**
	 * 캠페인 2건 — ACCOUNT 감시중(후보·프로필 스냅샷)과 POST 추적중(게시물 스냅샷 이틀치).
	 * 뷰가 캠페인별로 갈라지는지 보려면 target이 둘 이상이어야 한다.
	 */
	private void seed() {
		long watching = db.queryForObject("""
				INSERT INTO target (type, username, keyword_rule, status, registration_key, expires_at)
				VALUES ('ACCOUNT', 'acct_a', '{"and":["샤넬"],"any":[],"exclude":[]}'::jsonb,
				        'WATCHING', 'key-watching', now() + interval '30 days')
				RETURNING id""", Long.class);
		// PENDING 1건 + REJECTED 1건 — 상태 필터가 없으면 후보 수가 2로 샌다.
		db.update("INSERT INTO detected_candidate (target_id, short_code, status) VALUES (?, 'SC_P', 'PENDING')",
				watching);
		db.update("INSERT INTO detected_candidate (target_id, short_code, status) VALUES (?, 'SC_R', 'REJECTED')",
				watching);
		// 프로필 스냅샷 이틀치 — LATERAL이 최신 하루만 집는지 확인용.
		db.update("""
				INSERT INTO profile_snapshot (username, captured_on, followers, following, media_count)
				VALUES ('acct_a', DATE '2026-07-27', 1000, 10, 50),
				       ('acct_a', DATE '2026-07-28', 1200, 11, 52)""");

		db.update("""
				INSERT INTO target (type, username, short_code, status, tracked_short_code, tracked_since,
				                    registration_key, expires_at)
				VALUES ('POST', 'acct_b', 'SC1', 'TRACKING', 'SC1', now(),
				        'key-tracking', now() + interval '30 days')""");
		// 게시물 스냅샷도 이틀치 — 피드 게시물이라 views는 항상 null(delta도 null로 나와야 한다).
		db.update("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type,
				                           likes, comments, views, saves, shares, reposts)
				VALUES ('acct_b', 'SC1', DATE '2026-07-27', 'FEED', 100, 5, NULL, 3, 1, 0),
				       ('acct_b', 'SC1', DATE '2026-07-28', 'FEED', 130, 9, NULL, 4, 1, 2)""");
	}

	@Test
	void 개요_뷰는_최신_프로필_스냅샷과_PENDING_후보_수를_준다() {
		assertThat(db.queryForObject("SELECT count(*) FROM v_target_overview", Long.class)).isEqualTo(2);

		var row = db.queryForMap("SELECT * FROM v_target_overview WHERE username = 'acct_a'");
		assertThat(row.get("status")).isEqualTo("WATCHING");
		assertThat(row.get("registration_key")).isEqualTo("key-watching");
		assertThat(row.get("pending_candidates")).isEqualTo(1L);
		assertThat(row.get("profile_captured_on")).isEqualTo(Date.valueOf(LocalDate.of(2026, 7, 28)));
		assertThat(row.get("followers")).isEqualTo(1200L);
		assertThat(row.get("media_count")).isEqualTo(52L);

		// 프로필 스냅샷이 없는 캠페인도 목록에서 빠지지 않는다(LEFT JOIN LATERAL).
		var noProfile = db.queryForMap("SELECT * FROM v_target_overview WHERE username = 'acct_b'");
		assertThat(noProfile.get("followers")).isNull();
		assertThat(noProfile.get("pending_candidates")).isEqualTo(0L);
	}

	@Test
	void 개요_뷰는_추적_게시물의_최신_지표를_같이_준다() {
		// 캠페인 목록 화면은 이 뷰 하나로 서빙된다 — 추적 중이면 최신 하루치 지표가 붙는다.
		var tracking = db.queryForMap("SELECT * FROM v_target_overview WHERE registration_key = 'key-tracking'");
		assertThat(tracking.get("tracked_short_code")).isEqualTo("SC1");
		assertThat(tracking.get("post_captured_on")).isEqualTo(Date.valueOf(LocalDate.of(2026, 7, 28)));
		assertThat(tracking.get("content_type")).isEqualTo("FEED");
		// 첫날 값(100)이 아니라 최신 값 — LATERAL의 ORDER BY DESC LIMIT 1이 빠지면 여기서 깨진다.
		assertThat(tracking.get("likes")).isEqualTo(130L);
		assertThat(tracking.get("comments")).isEqualTo(9L);
		assertThat(tracking.get("saves")).isEqualTo(4L);
		assertThat(tracking.get("views")).isNull();   // 피드 조회수는 항상 null

		// 미추적(WATCHING) 캠페인은 게시물 컬럼이 전부 null — INNER JOIN이면 목록에서 사라진다.
		var watching = db.queryForMap("SELECT * FROM v_target_overview WHERE registration_key = 'key-watching'");
		assertThat(watching.get("tracked_short_code")).isNull();
		assertThat(watching.get("post_captured_on")).isNull();
		assertThat(watching.get("content_type")).isNull();
		assertThat(watching.get("likes")).isNull();
		assertThat(watching.get("comments")).isNull();
	}

	@Test
	void 추이_뷰는_전일_대비_증감을_계산한다() {
		var rows = db.queryForList(
				"SELECT * FROM v_target_timeseries WHERE target_id = "
						+ "(SELECT id FROM target WHERE registration_key = 'key-tracking') ORDER BY captured_on");

		assertThat(rows).hasSize(2);
		// 첫날은 비교 대상이 없어 delta가 null — 0으로 채우면 증감을 왜곡한다.
		assertThat(rows.get(0).get("likes_delta")).isNull();
		assertThat(rows.get(1).get("likes")).isEqualTo(130L);
		assertThat(rows.get(1).get("likes_delta")).isEqualTo(30L);
		assertThat(rows.get(1).get("comments_delta")).isEqualTo(4L);
		assertThat(rows.get(1).get("saves_delta")).isEqualTo(1L);
		assertThat(rows.get(1).get("shares_delta")).isEqualTo(0L);
		assertThat(rows.get(1).get("reposts_delta")).isEqualTo(2L);
		// 조회수 null 규칙 — 값이 null이면 delta도 null.
		assertThat(rows.get(1).get("views_delta")).isNull();
	}

	@Test
	void was_reader는_조회_표면을_읽을_수_있다() {
		assertThat(wasReader.queryForObject("SELECT count(*) FROM v_target_overview", Long.class)).isEqualTo(2);
		assertThat(wasReader.queryForObject("SELECT count(*) FROM v_target_timeseries", Long.class)).isEqualTo(2);
		assertThat(wasReader.queryForObject("SELECT count(*) FROM target", Long.class)).isEqualTo(2);
	}

	@Test
	void was_reader는_쓰기가_거부된다() {
		assertThatThrownBy(() -> wasReader.update("""
				INSERT INTO target (type, username, status, registration_key, expires_at)
				VALUES ('ACCOUNT', 'acct_x', 'WATCHING', 'key-x', now())"""))
				.hasStackTraceContaining("permission denied");
	}

	@Test
	void was_reader는_raw_스키마에_접근할_수_없다() {
		assertThatThrownBy(() -> wasReader.queryForObject("SELECT count(*) FROM raw.fetch_payload", Long.class))
				.hasStackTraceContaining("permission denied");
	}
}
