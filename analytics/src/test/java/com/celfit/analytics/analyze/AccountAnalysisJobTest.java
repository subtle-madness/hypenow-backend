package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AccountCopy;
import com.celfit.analytics.llm.AccountSynthesisPort;
import com.celfit.analytics.llm.AccountToAnalyze;
import com.celfit.analytics.testsupport.TestDb;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 계정 카피 배치 계약 (스펙 §2·§4):
 * ① 신규 즉시 분석·저장(adHeadline 조건부·traits jsonb 포함) ② 입력 동일 스킵
 * ③ stale인데 쿨다운 미경과 제외 ④ stale+쿨다운 경과 재분석 — 이력 2행
 * ⑤ 배치 상한 ⑥ 빈 카피 실패 격리 ⑦ traits 5개 절단.
 */
@Testcontainers
class AccountAnalysisJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	DataSource ds;
	AccountAnalysisJob job;
	List<AccountToAnalyze> calls;

	/** fake 포트: 호출 기록 + 고정 응답. adHeadline은 항상 채워 반환 — 조건부 NULL은 잡의 책임임을 검증. */
	AccountSynthesisPort fakePort() {
		return account -> {
			calls.add(account);
			return new AccountCopy("태그라인: " + account.handle(), "요약 문단", "흐름 문구", "차트 캡션",
					List.of("저자극", "성분리뷰", "정보형"), "광고 헤드라인", "페이스 문구");
		};
	}

	void rewireJob(AccountSynthesisPort port) {
		job = new AccountAnalysisJob(ds, port, new AnalyticsSettings(db), ProgressReporter.NOOP);
	}

	@BeforeEach
	void setUp() {
		ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		calls = new ArrayList<>();
		TestDb.resetAndMigrate(db, ds);
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");

		// C1 미러 시드: acct_ad(광고 비교 있음), acct_noad(광고 없음 — ad_avg NULL)
		db.update("""
				INSERT INTO account_summaries (handle, followers, analyzed_count, views_count, metric,
				  organic_avg, ad_avg, last_posted_at) VALUES
				  ('acct_ad',   10000, 6, 6, 'views', 13500, 15000, timestamptz '2026-07-01 09:00:00+09'),
				  ('acct_noad',  8000, 4, 4, 'views', 10375, NULL,  timestamptz '2026-07-02 09:00:00+09')""");
		db.update("""
				INSERT INTO account_category_stats (account_handle, main_group, content_count) VALUES
				  ('acct_ad', 'B', 6), ('acct_noad', 'B', 4)""");
		db.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
				  views, likes, comments, sponsored) VALUES
				  ('p1', 'acct_ad',   timestamptz '2026-06-01 09:00:00+09', 'reels', 20000, 400, 40, false),
				  ('p2', 'acct_ad',   timestamptz '2026-07-01 09:00:00+09', 'reels', 22000, 500, 50, true),
				  ('p3', 'acct_noad', timestamptz '2026-07-02 09:00:00+09', 'feed',  NULL,  200, 20, false)""");
		db.update("""
				INSERT INTO contents (short_code, account_handle, caption, content_type) VALUES
				  ('p1', 'acct_ad', '캡션1', 'reels'), ('p2', 'acct_ad', '캡션2', 'reels'),
				  ('p3', 'acct_noad', '캡션3', 'feed')""");

		rewireJob(fakePort());
	}

	@Test
	void 신규_계정은_즉시_분석되고_카피가_저장된다() {
		int processed = job.run().processed();

		assertEquals(2, processed);
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
		assertEquals("태그라인: acct_ad", db.queryForObject(
				"SELECT tagline FROM account_analyses WHERE handle = 'acct_ad'", String.class));
		// traits는 jsonb 배열로 저장된다
		assertEquals("저자극", db.queryForObject(
				"SELECT traits->>0 FROM account_analyses WHERE handle = 'acct_ad'", String.class));
		// input 스냅샷 = 분석 당시 미러 값
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad' AND input_last_posted_at = timestamptz '2026-07-01 09:00:00+09'",
				Long.class));
	}

	@Test
	void adHeadline은_광고_비교가_있는_계정에만_저장된다() {
		job.run();

		// fake 포트는 둘 다 헤드라인을 반환하지만, 비교 없는 계정은 잡이 NULL로 저장한다
		assertEquals("광고 헤드라인", db.queryForObject(
				"SELECT ad_headline FROM account_analyses WHERE handle = 'acct_ad'", String.class));
		assertNull(db.queryForObject(
				"SELECT ad_headline FROM account_analyses WHERE handle = 'acct_noad'", String.class));
		// 포트 입력의 hasAdComparison 플래그도 정확해야 한다 (어댑터가 지시문에서 분기)
		assertTrue(calls.stream().filter(c -> c.handle().equals("acct_ad")).findFirst().orElseThrow().hasAdComparison());
		assertFalse(calls.stream().filter(c -> c.handle().equals("acct_noad")).findFirst().orElseThrow().hasAdComparison());
	}

	@Test
	void 입력이_같으면_재분석하지_않는다() {
		job.run();
		calls.clear();

		int processed = job.run().processed();

		assertEquals(0, processed);
		assertTrue(calls.isEmpty());
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
	}

	@Test
	void stale여도_쿨다운_미경과면_재분석하지_않는다() {
		job.run(); // 최초 분석 (analyzed_at = now)
		calls.clear();
		// 새 게시물 유입으로 stale
		db.update("UPDATE account_summaries SET last_posted_at = timestamptz '2026-07-10 09:00:00+09' WHERE handle = 'acct_ad'");

		int processed = job.run().processed(); // 쿨다운 기본 7일 — 방금 분석했으므로 미경과

		assertEquals(0, processed);
		assertTrue(calls.isEmpty());
	}

	@Test
	void stale이고_쿨다운이_지나면_재분석되어_이력이_쌓인다() {
		job.run();
		calls.clear();
		db.update("UPDATE account_summaries SET last_posted_at = timestamptz '2026-07-10 09:00:00+09' WHERE handle = 'acct_ad'");
		// 기존 분석을 8일 전으로 백데이트 — 쿨다운(7일) 경과 재현
		db.update("UPDATE account_analyses SET analyzed_at = now() - interval '8 days' WHERE handle = 'acct_ad'");

		int processed = job.run().processed();

		assertEquals(1, processed); // acct_ad만 (acct_noad는 입력 동일)
		assertEquals(2L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'", Long.class)); // 이력 2행
		// 최신 행의 input 스냅샷이 갱신된 last_posted_at
		assertEquals(1, db.queryForObject("""
				SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'
				  AND input_last_posted_at = timestamptz '2026-07-10 09:00:00+09'
				  AND analyzed_at = (SELECT max(analyzed_at) FROM account_analyses WHERE handle = 'acct_ad')""",
				Integer.class));
	}

	@Test
	void 배치_상한을_지킨다() {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.account-analyze-batch-limit', '1')");

		int processed = job.run().processed(); // 신규 2계정 중 1건만

		assertEquals(1, processed);
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
	}

	@Test
	void 빈_카피는_저장하지_않고_다른_계정은_처리된다() {
		rewireJob(account -> {
			calls.add(account);
			if (account.handle().equals("acct_ad")) {
				return new AccountCopy("", "", "흐름", "차트", List.of("태그"), "", "페이스");
			}
			return new AccountCopy("태그라인", "요약", "흐름", "차트", List.of("태그", "태그2", "태그3"), "", "페이스");
		});

		int processed = job.run().processed(); // 예외가 전파되지 않아야 한다

		assertEquals(1, processed); // acct_noad만 성공
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'", Long.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_noad'", Long.class));
	}

	@Test
	void traits가_5개를_넘으면_앞_5개만_저장한다() {
		rewireJob(account -> {
			calls.add(account);
			return new AccountCopy("태그라인", "요약", "흐름", "차트",
					List.of("t1", "t2", "t3", "t4", "t5", "t6"), "", "페이스");
		});

		job.run();

		assertEquals(5, db.queryForObject(
				"SELECT jsonb_array_length(traits) FROM account_analyses WHERE handle = 'acct_ad'", Integer.class));
		assertEquals("t5", db.queryForObject(
				"SELECT traits->>4 FROM account_analyses WHERE handle = 'acct_ad'", String.class));
	}
}
