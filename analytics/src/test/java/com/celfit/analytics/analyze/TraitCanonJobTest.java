package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.celfit.analytics.llm.TraitMappingPort;
import com.celfit.analytics.llm.TraitTaxonomyLoader;
import com.celfit.analytics.testsupport.TestDb;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 배치 매핑(2026-07-29 스펙 §3-3): DRY=LLM 매핑→canon_log 기록만, APPLY=traits in-place UPDATE.
 * 1:N 분해·어휘 밖 캐노니컬 방어·매핑 불가('' 센티널)·재실행 시 canon_log 재사용을 고정한다.
 */
@Testcontainers
class TraitCanonJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static DataSource ds;
	static JdbcTemplate db;

	@BeforeAll
	static void migrate() {
		ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
	}

	@BeforeEach
	void seed() {
		db.update("TRUNCATE account_analyses, trait_canon_log");
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, tagline, traits, perf_summary, content_summary)
				VALUES ('a', now(), 'test', 't', '["감성 브이로그","솔직한 후기","솔직 리뷰"]'::jsonb, 'p', 'c'),
				       ('b', now(), 'test', 't', '["조어불가값","배신값"]'::jsonb, 'p', 'c')""");
	}

	/** 어휘 밖 캐노니컬("어휘밖태그")을 돌려주는 배신 케이스 포함 — 잡이 걸러야 한다. */
	static TraitMappingPort fakePort() {
		return raws -> Map.of(
				"감성 브이로그", List.of("브이로그", "감성 무드"),
				"솔직한 후기", List.of("솔직 리뷰"),
				"조어불가값", List.of(),
				"배신값", List.of("어휘밖태그"));
	}

	static TraitCanonJob job(TraitMappingPort port) {
		return new TraitCanonJob(ds, new TraitTaxonomyLoader(ds), port,
				ProgressReporter.NOOP, ProgressReporter.NOOP);
	}

	@Test
	void DRY는_canon_log만_쓰고_traits는_그대로다() {
		job(fakePort()).run(true);

		// 어휘 값("솔직 리뷰")은 항등이라 LLM 대상이 아니다 — canon_log 대상은 어휘 밖 raw 4개
		assertEquals(4L, db.queryForObject(
				"SELECT count(DISTINCT raw_value) FROM trait_canon_log", Long.class));
		// 1:N 분해는 raw당 복수 행
		assertEquals(2L, db.queryForObject(
				"SELECT count(*) FROM trait_canon_log WHERE raw_value='감성 브이로그'", Long.class));
		// 매핑 불가('조어불가값')와 어휘 밖 캐노니컬만 온 '배신값'은 '' 센티널 1행
		assertEquals("", db.queryForObject(
				"SELECT canon_value FROM trait_canon_log WHERE raw_value='조어불가값'", String.class));
		assertEquals("", db.queryForObject(
				"SELECT canon_value FROM trait_canon_log WHERE raw_value='배신값'", String.class));
		// traits는 그대로
		assertEquals("[\"감성 브이로그\", \"솔직한 후기\", \"솔직 리뷰\"]", db.queryForObject(
				"SELECT traits::text FROM account_analyses WHERE handle='a'", String.class));
	}

	@Test
	void APPLY는_분해_치환_드롭_중복제거로_UPDATE한다() {
		job(fakePort()).run(false);

		// 감성 브이로그→[브이로그, 감성 무드], 솔직한 후기→솔직 리뷰(원래 있던 솔직 리뷰와 중복 접힘)
		assertEquals("[\"브이로그\", \"감성 무드\", \"솔직 리뷰\"]", db.queryForObject(
				"SELECT traits::text FROM account_analyses WHERE handle='a'", String.class));
		// 전부 매핑 불가·배신 → 빈 배열
		assertEquals("[]", db.queryForObject(
				"SELECT traits::text FROM account_analyses WHERE handle='b'", String.class));
	}

	@Test
	void 재실행은_canon_log를_재사용해_LLM을_다시_부르지_않는다() {
		job(fakePort()).run(true);

		AtomicInteger llmCalls = new AtomicInteger();
		TraitMappingPort counting = raws -> {
			llmCalls.incrementAndGet();
			return Map.of();
		};
		job(counting).run(false);

		assertEquals(0, llmCalls.get());
		assertEquals("[\"브이로그\", \"감성 무드\", \"솔직 리뷰\"]", db.queryForObject(
				"SELECT traits::text FROM account_analyses WHERE handle='a'", String.class));
	}

	/** 응답에서 누락된 raw는 기록하지 않는다 — 다음 실행에서 재시도(일시 누락을 영구 드롭으로 만들지 않기). */
	@Test
	void 응답_누락_raw는_다음_실행_재대상으로_남는다() {
		TraitMappingPort partial = raws -> Map.of("솔직한 후기", List.of("솔직 리뷰"));
		job(partial).run(true);

		assertEquals(1L, db.queryForObject(
				"SELECT count(DISTINCT raw_value) FROM trait_canon_log", Long.class));
	}
}
