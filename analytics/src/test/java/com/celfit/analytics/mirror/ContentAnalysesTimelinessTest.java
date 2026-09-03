package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.celfit.analytics.testsupport.TestDb;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * content_analyses의 지표 시점 어휘 계약(2026-09-03 2단계 분리 §4-2).
 * 파트 A 행의 시점 값 'pending'을 CHECK가 허용해야 하고, 어휘 밖 값은 계속 거부해야 한다.
 * NULL을 쓰면 랭킹 6.1·카테고리 벤치마크 6.3이 레거시 timely로 취급해 미성숙 지표가 노출되고,
 * 'immature'를 재사용하면 어드민 퍼널의 종결 상태 집계가 오염된다 - 그래서 신규 어휘다.
 */
class ContentAnalysesTimelinessTest {

	static final PostgreSQLContainer pg = TestDb.shared();

	static JdbcTemplate db;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
	}

	private static void insertWithTimeliness(String shortCode, String timeliness) {
		db.update("INSERT INTO content_analyses (short_code, model, metric_timeliness) VALUES (?, ?, ?)",
				shortCode, "test-model", timeliness);
	}

	@Test
	void pending_어휘를_허용한다() {
		insertWithTimeliness("ca_pending", "pending");

		assertEquals("pending", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'ca_pending'",
				String.class));
	}

	@Test
	void 기존_어휘_3종도_그대로_허용한다() {
		insertWithTimeliness("ca_timely", "timely");
		insertWithTimeliness("ca_late", "late_backfill");
		insertWithTimeliness("ca_immature", "immature");

		assertEquals(3L, db.queryForObject("""
				SELECT count(*) FROM content_analyses
				WHERE short_code IN ('ca_timely', 'ca_late', 'ca_immature')""", Long.class));
	}

	@Test
	void 어휘_밖_값은_거부한다() {
		assertThrows(DataIntegrityViolationException.class,
				() -> insertWithTimeliness("ca_bogus", "processing"));
	}

	@Test
	void pending_부분_인덱스가_존재한다() {
		// 파트 B 후보를 '후보 ∩ pending' 포함 집합으로 좁히는 조회가 이 인덱스에 기댄다
		assertEquals(1L, db.queryForObject("""
				SELECT count(*) FROM pg_indexes
				WHERE schemaname = 'public' AND tablename = 'content_analyses'
				  AND indexname = 'idx_content_analyses_timeliness_pending'""", Long.class));
	}

	@Test
	void content_batch_jobs_kind_기본값은_analyze다() {
		// 롤링 창·롤백 직후 구 코드가 제출한 pending 행은 통합 파서로 처리돼야 한다
		db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count)
				VALUES ('batches/legacy', true, 1)""");

		assertEquals("analyze", db.queryForObject(
				"SELECT kind FROM content_batch_jobs WHERE batch_name = 'batches/legacy'", String.class));
	}

	@Test
	void content_batch_jobs_kind는_어휘_3종만_허용한다() {
		db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, kind)
				VALUES ('batches/facts', false, 1, 'facts')""");
		db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, kind)
				VALUES ('batches/synth', true, 1, 'synthesis')""");

		assertThrows(DataIntegrityViolationException.class, () -> db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, kind)
				VALUES ('batches/bogus', true, 1, 'unified')"""));
	}
}
