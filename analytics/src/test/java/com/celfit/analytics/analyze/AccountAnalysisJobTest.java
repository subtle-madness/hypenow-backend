package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AccountCopy;
import com.celfit.analytics.llm.AccountSynthesisPort;
import com.celfit.analytics.llm.AccountToAnalyze;
import com.celfit.analytics.llm.AdSituation;
import com.celfit.analytics.testsupport.TestDb;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 계정 카피 배치 계약 (스펙 §2·§4):
 * ① 신규 즉시 분석·저장(perf_summary·content_summary·ad_summary 신 3컬럼, 구 5컬럼은 전부 NULL,
 * ad_summary는 AdSituation 4분기에 따라 조건부) ② 입력 동일 스킵 ③ stale인데 쿨다운 미경과 제외
 * ④ stale+쿨다운 경과 재분석 — 이력 2행 ⑤ 배치 상한 ⑥ 빈 카피 실패 격리 ⑦ traits 5개 절단
 * ⑧ 구 스키마 행(perf_summary NULL)은 입력 동일해도 자연 재대상(07-27 개편 백필).
 */
@Testcontainers
class AccountAnalysisJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	DataSource ds;
	AccountAnalysisJob job;
	List<AccountToAnalyze> calls;

	/** fake 포트: 호출 기록 + 고정 응답. adSummary는 항상 채워 반환 — 조건부 NULL 처리는 잡(AdSituation)이 맡는다. */
	AccountSynthesisPort fakePort() {
		return account -> {
			calls.add(account);
			return new AccountCopy("태그라인: " + account.handle(),
					List.of("저자극", "성분리뷰", "정보형"), "성과 요약", "콘텐츠 요약", "광고 요약");
		};
	}

	void rewireJob(AccountSynthesisPort port) {
		job = new AccountAnalysisJob(ds, port, new AnalyticsSettings(db), ProgressReporter.NOOP);
	}

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		calls = new ArrayList<>();
		TestDb.resetAndMigrate(db, ds);
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");

		// C1 미러 시드 — 광고 정본은 content_analyses.ad_type(캡션 분류)이고, 미러의
		// account_summaries.organic_avg/ad_avg·series.sponsored는 옛 소스(ad_marked, 릴스 전용)다.
		// 다섯 계정으로 두 소스가 어긋나는 경우를 모두 덮는다:
		//   acct_ad     — 두 소스 모두 광고 있음
		//   acct_noad   — 광고 없음
		//   acct_caption— 캡션 고지만(릴스 태그 없음): 옛 소스로는 차단됐던 케이스
		//   acct_tagonly— 릴스 태그만(캡션 분류는 organic): 화면에 비교가 안 뜨는 케이스
		//   acct_allads — 측정 가능분이 전량 협찬(비교 대상 organic 없음)
		db.update("""
				INSERT INTO account_summaries (handle, followers, analyzed_count, views_count, metric,
				  organic_avg, ad_avg, last_posted_at) VALUES
				  ('acct_ad',      10000, 6, 6, 'views', 13500, 15000, timestamptz '2026-07-01 09:00:00+09'),
				  ('acct_noad',     8000, 4, 4, 'views', 10375, NULL,  timestamptz '2026-07-02 09:00:00+09'),
				  ('acct_caption',  9000, 4, 4, 'views', 12000, NULL,  timestamptz '2026-07-03 09:00:00+09'),
				  ('acct_tagonly',  7000, 4, 4, 'views', 11000, 30000, timestamptz '2026-07-04 09:00:00+09'),
				  ('acct_allads',   6000, 4, 4, 'views', NULL,  9000,  timestamptz '2026-07-05 09:00:00+09')""");
		db.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
				  views, likes, comments, sponsored) VALUES
				  ('p1', 'acct_ad',      timestamptz '2026-06-01 09:00:00+09', 'reels', 20000, 400, 40, false),
				  ('p2', 'acct_ad',      timestamptz '2026-07-01 09:00:00+09', 'reels', 22000, 500, 50, true),
				  ('p3', 'acct_noad',    timestamptz '2026-07-02 09:00:00+09', 'feed',  NULL,  200, 20, false),
				  ('p4', 'acct_caption', timestamptz '2026-06-03 09:00:00+09', 'reels', 10000, 300, 30, false),
				  ('p5', 'acct_caption', timestamptz '2026-07-03 09:00:00+09', 'reels',  6000, 200, 20, false),
				  ('p6', 'acct_tagonly', timestamptz '2026-06-04 09:00:00+09', 'reels', 11000, 300, 30, false),
				  ('p7', 'acct_tagonly', timestamptz '2026-07-04 09:00:00+09', 'reels', 30000, 900, 90, true),
				  ('p8', 'acct_allads',  timestamptz '2026-06-05 09:00:00+09', 'reels',  8000, 250, 25, false),
				  ('p9', 'acct_allads',  timestamptz '2026-07-05 09:00:00+09', 'reels', 10000, 350, 35, false)""");
		db.update("""
				INSERT INTO contents (short_code, account_handle, caption, content_type) VALUES
				  ('p1', 'acct_ad', '캡션1', 'reels'), ('p2', 'acct_ad', '캡션2', 'reels'),
				  ('p3', 'acct_noad', '캡션3', 'feed'),
				  ('p4', 'acct_caption', '캡션4', 'reels'), ('p5', 'acct_caption', '#광고 캡션5', 'reels'),
				  ('p6', 'acct_tagonly', '캡션6', 'reels'), ('p7', 'acct_tagonly', '캡션7', 'reels'),
				  ('p8', 'acct_allads', '#광고 캡션8', 'reels'), ('p9', 'acct_allads', '#협찬 캡션9', 'reels')""");
		// 광고 정본 — p2·p5만 협찬. acct_tagonly는 릴스 태그(p7 sponsored=true)와 달리 캡션 분류상 organic.
		// 카테고리 믹스는 테이블이 아니라 파생 뷰다(V35) — 캡션 분류를 심으면 뷰가 집계해 준다.
		// short_code가 PK라 광고·카테고리를 한 번에 넣는다(따로 INSERT하면 p1·p2가 중복된다).
		db.update("""
				INSERT INTO content_analyses (short_code, model, main_category, is_beauty, ad_type) VALUES
				  ('p1', 'test', 'makeup',   true, 'organic'),
				  ('p2', 'test', 'makeup',   true, 'sponsored'),
				  ('p3', 'test', 'skincare', true, NULL),
				  ('p4', 'test', NULL,       NULL, 'organic'),
				  ('p5', 'test', NULL,       NULL, 'sponsored'),
				  ('p6', 'test', NULL,       NULL, 'organic'),
				  ('p7', 'test', NULL,       NULL, 'organic'),
				  ('p8', 'test', NULL,       NULL, 'sponsored'),
				  ('p9', 'test', NULL,       NULL, 'sponsored')""");

		rewireJob(fakePort());
	}

	@Test
	void 신규_계정은_즉시_분석되고_카피가_저장된다() {
		int processed = job.run().processed();

		assertEquals(5, processed);
		assertEquals(5L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
		assertEquals("태그라인: acct_ad", db.queryForObject(
				"SELECT tagline FROM account_analyses WHERE handle = 'acct_ad'", String.class));
		// traits는 jsonb 배열로 저장된다
		assertEquals("저자극", db.queryForObject(
				"SELECT traits->>0 FROM account_analyses WHERE handle = 'acct_ad'", String.class));
		// input 스냅샷 = 분석 당시 미러 값
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad' AND input_last_posted_at = timestamptz '2026-07-01 09:00:00+09'",
				Long.class));
		// 신 요약 3종 저장
		assertEquals("성과 요약", db.queryForObject(
				"SELECT perf_summary FROM account_analyses WHERE handle = 'acct_ad'", String.class));
		assertEquals("콘텐츠 요약", db.queryForObject(
				"SELECT content_summary FROM account_analyses WHERE handle = 'acct_ad'", String.class));
		// 구 카피 5컬럼은 07-27 개편 후 미기록(NULL)
		assertEquals(0L, db.queryForObject("""
				SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'
				  AND (summary IS NOT NULL OR trend_note IS NOT NULL OR chart_note IS NOT NULL
				       OR ad_headline IS NOT NULL OR pace_note IS NOT NULL)""", Long.class));
	}

	AccountToAnalyze callFor(String handle) {
		return calls.stream().filter(c -> c.handle().equals(handle)).findFirst().orElseThrow();
	}

	String adSummaryOf(String handle) {
		return db.queryForObject(
				"SELECT ad_summary FROM account_analyses WHERE handle = ?", String.class, handle);
	}

	@Test
	void 광고_상황별로_헤드라인_생성_여부가_갈린다() {
		job.run();

		// ① 비교 가능(organic·협찬 둘 다 측정 가능) — 현행 그대로 생성
		assertEquals(AdSituation.COMPARABLE, callFor("acct_ad").adSituation());
		assertEquals("광고 요약", adSummaryOf("acct_ad"));
		// ④ 지표 부족(측정 가능 게시물 없음 — 피드 조회수 NULL) — 근거가 없어 NULL 유지
		assertEquals(AdSituation.INSUFFICIENT, callFor("acct_noad").adSituation());
		assertNull(adSummaryOf("acct_noad"));
	}

	/** 버그 정본 케이스 — 캡션으로 협찬 고지했지만 릴스 유료파트너십 태그가 없는 계정. */
	@Test
	void 캡션_고지만_있는_계정도_비교_가능으로_인정된다() {
		job.run();

		assertEquals(AdSituation.COMPARABLE, callFor("acct_caption").adSituation());
		assertEquals("광고 요약", adSummaryOf("acct_caption"));
	}

	/**
	 * ② 협찬 이력 전무 — 예전엔 "비교 불가"라 NULL이었지만, 협찬이 없다는 사실 자체가
	 * 마케터에게 정보다(객관 진술). 헤드라인을 생성한다.
	 */
	@Test
	void 협찬이_전혀_없는_계정도_헤드라인을_받는다() {
		job.run();

		assertEquals(AdSituation.NO_ADS, callFor("acct_tagonly").adSituation());
		assertEquals("광고 요약", adSummaryOf("acct_tagonly"));
	}

	/**
	 * ③ 측정 가능분이 전량 협찬 — 비교 대상 organic이 없을 뿐, 협찬 비중·성과는 진술 가능하다.
	 */
	@Test
	void 전량_협찬_계정도_헤드라인을_받는다() {
		job.run();

		assertEquals(AdSituation.ALL_ADS, callFor("acct_allads").adSituation());
		assertEquals("광고 요약", adSummaryOf("acct_allads"));
	}

	/** 프롬프트 지시문이 평가·권유를 금지하고 상황별 진술을 요구해야 한다. */
	@Test
	void 지시문이_평가를_금지하고_상황별_진술을_지시한다() {
		String instructions = com.celfit.analytics.llm.GeminiAccountSynthesizer.instructions();

		assertTrue(instructions.contains("좋다"), instructions);
		assertTrue(instructions.contains(AdSituation.NO_ADS.label()), instructions);
		assertTrue(instructions.contains(AdSituation.ALL_ADS.label()), instructions);
	}

	/** 프롬프트에 실리는 광고 수치도 정본(ad_type) 기준이어야 한다 — 헤드라인이 설명하는 숫자와
	 *  화면 숫자가 갈리면 안 된다. acct_tagonly: 옛 소스는 organic 11000/ad 30000이지만
	 *  정본으로는 p6·p7 모두 organic → 비교 평균 없음(NULL)·협찬 0건. */
	@Test
	void 프롬프트_광고_수치가_정본_기준으로_치환된다() {
		job.run();

		Map<String, Object> caption = callFor("acct_caption").summary();
		assertEquals(10000L, caption.get("organic_avg")); // p4
		assertEquals(6000L, caption.get("ad_avg")); // p5
		assertEquals(40, caption.get("ad_drop_pct")); // (1 - 6000/10000) * 100
		assertEquals(1L, caption.get("sponsored_count"));
		assertEquals(1L, caption.get("comparison_organic_count"));
		assertEquals(1L, caption.get("comparison_ad_count"));

		Map<String, Object> tagOnly = callFor("acct_tagonly").summary();
		assertNull(tagOnly.get("organic_avg"));
		assertNull(tagOnly.get("ad_avg"));
		assertNull(tagOnly.get("ad_drop_pct"));
		assertEquals(0L, tagOnly.get("sponsored_count"));
	}

	/** 게시물별 sponsored 플래그도 같은 정본 — 요약은 협찬 1건인데 게시물은 전부 false 같은
	 *  자기모순 입력이 LLM에 들어가지 않게 한다. */
	@Test
	void 프롬프트_게시물_sponsored도_정본_기준이다() {
		job.run();

		List<Map<String, Object>> posts = callFor("acct_caption").posts();
		assertEquals(List.of(false, true), posts.stream().map(p -> p.get("sponsored")).toList());
		assertEquals(List.of(false, false),
				callFor("acct_tagonly").posts().stream().map(p -> p.get("sponsored")).toList());
	}

	@Test
	void 입력이_같으면_재분석하지_않는다() {
		job.run();
		calls.clear();

		int processed = job.run().processed();

		assertEquals(0, processed);
		assertTrue(calls.isEmpty());
		assertEquals(5L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
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

	/** perfSummary·contentSummary 둘 다 빈 카피 가드 대상 — 어느 쪽이 비어도 저장을 막는다. */
	@Test
	void 빈_카피는_저장하지_않고_다른_계정은_처리된다() {
		rewireJob(account -> {
			calls.add(account);
			if (account.handle().equals("acct_ad")) {
				return new AccountCopy("태그라인", List.of("태그"), "", "콘텐츠 요약", "광고 요약"); // perfSummary 공백
			}
			if (account.handle().equals("acct_noad")) {
				return new AccountCopy("태그라인", List.of("태그"), "성과 요약", "", "광고 요약"); // contentSummary 공백
			}
			return new AccountCopy("태그라인", List.of("태그", "태그2", "태그3"), "성과 요약", "콘텐츠 요약", "광고 요약");
		});

		int processed = job.run().processed(); // 예외가 전파되지 않아야 한다

		assertEquals(3, processed); // acct_ad·acct_noad만 실패
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'", Long.class));
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_noad'", Long.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_caption'", Long.class));
	}

	/**
	 * 07-27 개편 백필 — 신 스키마 이전에 쌓인 행은 input(last_posted_at)이 미러와 같아도
	 * perf_summary가 없으면 스킵 대상에서 빠져 재분석된다(구 스키마 자연 재대상).
	 * analyzed_at을 쿨다운(기본 7일) 창 안(1시간 전)으로 둬 "쿨다운을 무시하고 즉시 재대상"임을
	 * 증명한다 — stale 재분석 분기(쿨다운 경과 필요)와 섞이면 이 테스트가 false negative를 놓친다.
	 */
	@Test
	void 구_스키마_행은_perf_summary가_비어_재대상이_된다() {
		// 신 스키마 이전에 쌓인 행: 입력 동일(stale 아님)+쿨다운 미경과인데도 perf_summary가 없다
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  input_analyzed_count, tagline, summary, traits)
				VALUES ('acct_ad', now() - interval '1 hour', 'm',
				  timestamptz '2026-07-01 09:00:00+09', 6, '옛 태그라인', '옛 요약', '["a"]'::jsonb)""");
		rewireJob(fakePort());

		job.run();

		// 재분석돼 이력 2행, 최신 행에는 perf_summary가 있다
		assertEquals(2, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'", Integer.class));
		assertEquals("성과 요약", db.queryForObject("""
				SELECT perf_summary FROM account_analyses WHERE handle = 'acct_ad'
				ORDER BY analyzed_at DESC LIMIT 1""", String.class));
	}

	@Test
	void traits가_5개를_넘으면_앞_5개만_저장한다() {
		rewireJob(account -> {
			calls.add(account);
			return new AccountCopy("태그라인",
					List.of("t1", "t2", "t3", "t4", "t5", "t6"), "성과 요약", "콘텐츠 요약", "");
		});

		job.run();

		assertEquals(5, db.queryForObject(
				"SELECT jsonb_array_length(traits) FROM account_analyses WHERE handle = 'acct_ad'", Integer.class));
		assertEquals("t5", db.queryForObject(
				"SELECT traits->>4 FROM account_analyses WHERE handle = 'acct_ad'", String.class));
	}
}
