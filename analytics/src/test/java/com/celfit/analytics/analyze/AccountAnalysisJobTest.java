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
import com.celfit.analytics.llm.CopyRules;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.testsupport.TestDb;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

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
	ObjectMapper om = new ObjectMapper();

	/** fake 배치 제출 API — 업로드 바이트·생성 인자를 기록. 콘텐츠(ContentAnalysisJobTest.fakeBatchApi) 동형. */
	List<byte[]> batchUploads;
	List<String> batchCreated;

	/** fake 포트: 호출 기록 + 고정 응답(traits는 V41 어휘 값 — 어휘 밖은 sanitize가 드롭한다).
	 *  adSummary는 항상 채워 반환 — 조건부 NULL 처리는 잡(AdSituation)이 맡는다. */
	AccountSynthesisPort fakePort() {
		return account -> {
			calls.add(account);
			return new AccountCopy("태그라인: " + account.handle(),
					List.of("성분 분석", "정보형 콘텐츠", "솔직 리뷰"), "성과 요약", "콘텐츠 요약", "광고 요약");
		};
	}

	void rewireJob(AccountSynthesisPort port) {
		rewireJob(port, null);
	}

	void rewireJob(AccountSynthesisPort port, GeminiBatchApi batchApi) {
		job = new AccountAnalysisJob(ds, port, new AnalyticsSettings(db), ProgressReporter.NOOP,
				new com.celfit.analytics.llm.TraitTaxonomyLoader(ds), batchApi);
	}

	/** 제출만 검증하는 테스트용 — 배치 상태 조회는 항상 실행 중, 다운로드는 호출되면 실패. */
	GeminiBatchApi fakeBatchApi() {
		return new GeminiBatchApi() {
			@Override
			public String uploadFile(byte[] jsonl, String displayName) {
				batchUploads.add(jsonl);
				return "files/fake";
			}

			@Override
			public String createBatch(String model, String inputFileName, String displayName) {
				batchCreated.add(model + "|" + inputFileName);
				return "batches/fake";
			}

			@Override
			public String getBatch(String batchName) {
				return "{\"metadata\":{\"state\":\"JOB_STATE_RUNNING\"}}";
			}

			@Override
			public void downloadResults(String fileName, Consumer<String> onLine) {
				throw new IllegalStateException("제출 테스트에서는 호출되면 안 됨");
			}
		};
	}

	/** 제출 전 pending 수거 테스트용 — getBatch/downloadResults는 SUCCEEDED 고정 응답을 돌려주고,
	 *  이어지는 submitBatch의 uploadFile/createBatch도 함께 지원한다. */
	GeminiBatchApi sweepingBatchApi(String resultFile, String resultJsonl) {
		return new GeminiBatchApi() {
			@Override
			public String uploadFile(byte[] jsonl, String displayName) {
				batchUploads.add(jsonl);
				return "files/fake";
			}

			@Override
			public String createBatch(String model, String inputFileName, String displayName) {
				batchCreated.add(model + "|" + inputFileName);
				return "batches/fake";
			}

			@Override
			public String getBatch(String batchName) {
				return """
						{"name":"%s","metadata":{"state":"JOB_STATE_SUCCEEDED",
						 "output":{"responsesFile":"%s"}}}""".formatted(batchName, resultFile);
			}

			@Override
			public void downloadResults(String fileName, Consumer<String> onLine) {
				resultJsonl.lines().filter(l -> !l.isBlank()).forEach(onLine);
			}
		};
	}

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		calls = new ArrayList<>();
		batchUploads = new ArrayList<>();
		batchCreated = new ArrayList<>();
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
		// 신뢰도 판정 재료 9컬럼(V44)도 함께 채워 "정상적으로 미러된 계정" 상태를 흉내낸다 — 값 자체는
		// 이 테스트들의 관심사가 아니라 임의로 골랐다. 9개 전부 NULL(=데이터 미비/미러 갭)인 케이스는
		// 별도 픽스처(아래 dataIncomplete 관련 테스트)에서만 재현한다.
		db.update("""
				INSERT INTO account_summaries (handle, followers, analyzed_count, views_count, metric,
				  organic_avg, ad_avg, last_posted_at,
				  views_sample_count, likes_sample_count, comments_sample_count, reels_count, feed_count,
				  median_views, median_er_pct, top_views_share_pct, window_span_days) VALUES
				  ('acct_ad',      10000, 6, 6, 'views', 13500, 15000, timestamptz '2026-07-01 09:00:00+09',
				    6, 6, 6, 6, 0, 15000, 2.0, 55, 30),
				  ('acct_noad',     8000, 4, 4, 'views', 10375, NULL,  timestamptz '2026-07-02 09:00:00+09',
				    4, 4, 4, 0, 4, NULL, 1.5, NULL, 10),
				  ('acct_caption',  9000, 4, 4, 'views', 12000, NULL,  timestamptz '2026-07-03 09:00:00+09',
				    4, 4, 4, 4, 0, 8000, 1.2, 60, 30),
				  ('acct_tagonly',  7000, 4, 4, 'views', 11000, 30000, timestamptz '2026-07-04 09:00:00+09',
				    4, 4, 4, 4, 0, 11000, 1.4, 65, 30),
				  ('acct_allads',   6000, 4, 4, 'views', NULL,  9000,  timestamptz '2026-07-05 09:00:00+09',
				    4, 4, 4, 4, 0, 9000, 1.1, 70, 30)""");
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
		assertEquals("성분 분석", db.queryForObject(
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
		String instructions = com.celfit.analytics.llm.GeminiAccountSynthesizer.instructions(
				new com.celfit.analytics.llm.TraitTaxonomyLoader(ds).get(),
				com.celfit.analytics.llm.PerfConfidence.none());

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
					List.of("릴스 중심", "브이로그", "튜토리얼", "언박싱", "하울", "GRWM"),
					"성과 요약", "콘텐츠 요약", "");
		});

		job.run();

		assertEquals(5, db.queryForObject(
				"SELECT jsonb_array_length(traits) FROM account_analyses WHERE handle = 'acct_ad'", Integer.class));
		assertEquals("하울", db.queryForObject(
				"SELECT traits->>4 FROM account_analyses WHERE handle = 'acct_ad'", String.class));
	}

	/** 어휘 통제(2026-07-29 스펙 §3-2): 어휘 밖 산출은 저장에서 드롭 — 전부 밖이면 빈 배열. */
	@Test
	void 어휘_밖_traits는_드롭되고_빈_배열이_허용된다() {
		rewireJob(account -> {
			calls.add(account);
			return new AccountCopy("태그라인",
					List.of("저자극", "임의조어"), "성과 요약", "콘텐츠 요약", "");
		});

		job.run();

		assertEquals(0, db.queryForObject(
				"SELECT jsonb_array_length(traits) FROM account_analyses WHERE handle = 'acct_ad'", Integer.class));
	}

	/**
	 * 성과 요약 통계 왜곡 가드(설계 §3-3 재정의) — 항상 제거할 판정 전용 내부 컬럼은 7개뿐이다.
	 * {@code median_views}·{@code median_er_pct}는 여기 없다 — "수준 판정의 근거를 median으로
	 * 옮긴다"는 간판 결정 그 자체라 LLM이 봐야 하는 값이다(예전엔 이 둘도 always-strip에 있어서
	 * 간판 변경이 무력화됐었다 — {@code 777minseo} 사례, PerfConfidence.CONFIDENCE_COLUMNS javadoc
	 * 참조). 다만 median이 존재하면 대응 avg는 "선택지"가 아니라 median을 유일한 근거로 만들기
	 * 위해 제거된다(뒤 테스트에서 검증). 판정 자체(PerfConfidence)는 PerfConfidenceTest가
	 * 별도로 검증한다.
	 */
	@Test
	void 프롬프트_요약에서_always_strip_7개만_제거되고_median_두_개는_남는다() {
		db.update("""
				UPDATE account_summaries SET
				  views_sample_count = 6, likes_sample_count = 6, comments_sample_count = 6,
				  reels_count = 6, feed_count = 0, median_views = 9000, median_er_pct = 1.2,
				  top_views_share_pct = 55, window_span_days = 10
				WHERE handle = 'acct_ad'""");

		job.run();

		Map<String, Object> summary = callFor("acct_ad").summary();
		for (String key : List.of("views_sample_count", "likes_sample_count", "comments_sample_count",
				"reels_count", "feed_count", "top_views_share_pct", "window_span_days")) {
			assertFalse(summary.containsKey(key), key + "가 프롬프트 입력에 남아 있음: " + summary);
		}
		assertTrue(summary.containsKey("median_views"),
				"median_views가 프롬프트 입력에서 빠짐(간판 변경 무력화): " + summary);
		assertTrue(summary.containsKey("median_er_pct"),
				"median_er_pct가 프롬프트 입력에서 빠짐(간판 변경 무력화): " + summary);
		// median이 존재하니 대응 avg는 선택지가 아니라 제거 대상 — median을 유일한 근거로 만든다.
		assertFalse(summary.containsKey("avg_views"), "median_views가 있는데 avg_views가 남아 있음: " + summary);
		assertFalse(summary.containsKey("views_per_follower"),
				"median_views가 있는데 views_per_follower가 남아 있음: " + summary);
		assertFalse(summary.containsKey("avg_er_pct"), "median_er_pct가 있는데 avg_er_pct가 남아 있음: " + summary);
	}

	/**
	 * 조건부 제거(설계 §3-3 실측 보완 + §3-3 재정의) — "언급하지 마라"뿐인 지침은 안 지켜졌고
	 * 입력에서 아예 뺀 것만 지켜졌다. TOO_LONG이면 추세 4컬럼, 조회수 INSUFFICIENT면 avg_views·
	 * views_per_follower가 프롬프트 요약에서 빠지고, 게시물 목록에서도 views 필드가 빠져야 한다.
	 * 좋아요·댓글은 OK 등급이라 avg_likes·avg_comments는 그대로 남아야 한다.
	 *
	 * <p>이 픽스처는 median_views = NULL(조회수 관측 자체가 부족해 계산 불가)인데, 조회수가
	 * INSUFFICIENT라는 것만으로도 (median_views가 애초에 NULL이라 "존재 시 제거" 규칙과는
	 * 무관하게) 제거 대상에 들어가야 한다 — 두 규칙(모수 게이트 제거 vs median 존재 시 제거)이
	 * 중복 없이 합성되는지 확인한다. median_er_pct는 1.2로 채워져 있고 조회수와 무관한 지표라
	 * 그대로 노출되되, 존재하므로 대응 avg_er_pct는 제거돼야 한다.
	 */
	@Test
	void TOO_LONG과_조회수_INSUFFICIENT면_추세와_조회수_집계_median까지_프롬프트에서_빠진다() {
		db.update("""
				INSERT INTO account_summaries (handle, followers, analyzed_count, views_count, metric,
				  avg_views, views_per_follower, avg_er_pct, avg_likes, avg_comments,
				  trend_direction, trend_change_pct, trend_older_avg, trend_newer_avg,
				  last_posted_at,
				  views_sample_count, likes_sample_count, comments_sample_count, reels_count, feed_count,
				  median_views, median_er_pct, top_views_share_pct, window_span_days)
				VALUES ('acct_insufficient', 5000, 3, 1, 'views',
				  9000, 1.8, 3.0, 500, 50,
				  'down', -12, 12000, 8000,
				  timestamptz '2026-07-06 09:00:00+09',
				  1, 6, 6, 3, 0,
				  NULL, 1.2, 100, 400)""");
		db.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
				  views, likes, comments, sponsored) VALUES
				  ('q1', 'acct_insufficient', timestamptz '2026-07-06 09:00:00+09', 'reels', 20000, 500, 50, false)""");

		job.run();

		Map<String, Object> summary = callFor("acct_insufficient").summary();
		for (String key : List.of("avg_views", "views_per_follower", "median_views", "avg_er_pct",
				"trend_direction", "trend_change_pct", "trend_older_avg", "trend_newer_avg")) {
			assertFalse(summary.containsKey(key), key + "가 프롬프트 입력에 남아 있음: " + summary);
		}
		assertTrue(summary.containsKey("median_er_pct"),
				"조회수와 무관한 median_er_pct가 빠짐(조회수 INSUFFICIENT가 과잉 적용됨): " + summary);
		assertTrue(summary.containsKey("avg_likes"), "OK 등급인 avg_likes가 빠짐: " + summary);
		assertTrue(summary.containsKey("avg_comments"), "OK 등급인 avg_comments가 빠짐: " + summary);

		Map<String, Object> post = callFor("acct_insufficient").posts().get(0);
		assertFalse(post.containsKey("views"), "조회수 INSUFFICIENT인데 게시물 views가 남아 있음: " + post);
		assertTrue(post.containsKey("likes"), "OK 등급인 게시물 likes가 빠짐: " + post);
		assertTrue(post.containsKey("comments"), "OK 등급인 게시물 comments가 빠짐: " + post);
	}

	/** WEAK(3~5건)는 톤 연화만 목적이라 값이 필요하다 — 집계 키·게시물 필드 모두 그대로 남아야 한다. */
	@Test
	void WEAK_등급은_집계_키와_게시물_필드를_그대로_남긴다() {
		// acct_noad(setUp) — views/likes/comments_sample_count 전부 4(WEAK), window_span_days=10(OK)
		job.run();

		Map<String, Object> summary = callFor("acct_noad").summary();
		assertTrue(summary.containsKey("avg_views"), summary.toString());
		assertTrue(summary.containsKey("avg_likes"), summary.toString());
		assertTrue(summary.containsKey("avg_comments"), summary.toString());

		Map<String, Object> post = callFor("acct_noad").posts().get(0);
		assertTrue(post.containsKey("views"), post.toString());
		assertTrue(post.containsKey("likes"), post.toString());
		assertTrue(post.containsKey("comments"), post.toString());
	}

	/** 새로 생성된 카피는 항상 현재 CopyRules.VERSION으로 저장된다(설계 §4) — 아니면 무한 재대상 루프. */
	@Test
	void 신규_카피는_현재_카피_버전으로_저장된다() {
		job.run();

		assertEquals(CopyRules.VERSION, db.queryForObject(
				"SELECT copy_version FROM account_analyses WHERE handle = 'acct_ad'", Integer.class));
	}

	/**
	 * 버전 게이트(설계 §4) — 최신 행의 copy_version이 CopyRules.VERSION보다 낮으면, 입력이
	 * 동일하고 쿨다운도 미경과인 상태여도(다른 재대상 사유가 전혀 없어도) 재대상이 돼야 한다.
	 * 판정 규칙이 바뀌었을 때 기존 문구가 낡음으로 표시돼 자연 재생성되는 경로.
	 */
	@Test
	void 카피_버전이_낮으면_입력_동일_쿨다운_미경과여도_재대상이_된다() {
		job.run(); // acct_ad 최초 분석 — copy_version = CopyRules.VERSION으로 저장됨
		calls.clear();
		db.update("UPDATE account_analyses SET copy_version = 0 WHERE handle = 'acct_ad'");

		int processed = job.run().processed();

		assertTrue(calls.stream().anyMatch(c -> c.handle().equals("acct_ad")));
		assertEquals(1, processed); // acct_ad만 — 나머지는 입력 동일 + 최신 버전
		assertEquals(2L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_ad'", Long.class));
	}

	/**
	 * 배포 과도기 가드 — 뷰(10_account_detail.sql) 선적용 없이 V44 마이그레이션만 배포되면 미러가
	 * 신뢰도 판정 컬럼(뷰가 새로 추가한 9개 중 배포 과도기 감지에 쓰는 always-strip 7개,
	 * PerfConfidence.CONFIDENCE_COLUMNS)을 채우지 못한 채 ADD COLUMN 기본값(NULL)으로 남는다.
	 * 이 상태에서 카피를 만들면 모든 문장이 최대 억제 등급을 받고 그게 CopyRules.VERSION으로
	 * 영구 고정되므로(설계 §7), 잡은 아예 생성을 건너뛰어야 한다 — 이 테스트는 그 스킵을 못 박는다.
	 */
	@Test
	void 신뢰도_컬럼_7개가_전부_NULL이면_카피_생성을_건너뛴다() {
		// 9컬럼을 아예 지정하지 않아 ADD COLUMN 기본값(NULL)인 "미러 갭" 상태를 그대로 재현한다.
		db.update("""
				INSERT INTO account_summaries (handle, followers, analyzed_count, views_count, metric,
				  last_posted_at)
				VALUES ('acct_mirror_gap', 5000, 6, 6, 'views', timestamptz '2026-07-06 09:00:00+09')""");

		int processed = job.run().processed();

		// 나머지 5계정은 정상 처리되고(setUp에서 9컬럼을 채워둠), 미러 갭 계정만 스킵된다.
		assertEquals(5, processed);
		assertFalse(calls.stream().anyMatch(c -> c.handle().equals("acct_mirror_gap")),
				"미러 갭 계정이 LLM 호출까지 가면 안 된다: " + calls);
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_mirror_gap'", Long.class));
	}

	/**
	 * 피드 전용 계정(조회수 관측 자체가 없음)은 데이터 미비가 아니라 정상 판정을 받아야 한다 —
	 * views_sample_count=0·reels_count=0·feed_count=12처럼 값이 채워지지 NULL이 되지 않기 때문이다
	 * (뷰의 count(*) FILTER는 매치 0건이어도 정수 0을 반환 — PerfConfidence 클래스 javadoc 참조).
	 * 이 케이스를 데이터 미비로 오판하면 정상 계정이 영구 스킵된다.
	 */
	@Test
	void 피드_전용_계정은_데이터_미비가_아니라_정상_처리된다() {
		db.update("""
				INSERT INTO account_summaries (handle, followers, analyzed_count, views_count, metric,
				  last_posted_at,
				  views_sample_count, likes_sample_count, comments_sample_count, reels_count, feed_count,
				  median_views, median_er_pct, top_views_share_pct, window_span_days)
				VALUES ('acct_feed_only', 5000, 12, 0, 'likes', timestamptz '2026-07-06 09:00:00+09',
				  0, 12, 12, 0, 12, NULL, 1.8, NULL, 45)""");

		job.run();

		assertTrue(calls.stream().anyMatch(c -> c.handle().equals("acct_feed_only")),
				"피드 전용 계정이 데이터 미비로 오판돼 스킵됨: " + calls);
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct_feed_only'", Long.class));
	}

	/**
	 * 배치 전송(2026-08-17) — 계정 카피도 콘텐츠와 동형으로 온라인 대신 Vertex 배치 제출로 전환된다.
	 * batch-limit=1로 대상을 acct_ad 하나로 좁혀(ORDER BY handle ASC) 단언을 단순화한다.
	 */
	@Test
	void 배치_전송이면_제출만_하고_온라인_포트를_호출하지_않는다() {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.account-analyze-transport', 'batch')");
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.account-analyze-batch-limit', '1')");
		rewireJob(fakePort(), fakeBatchApi());

		JobResult result = job.run();

		assertTrue(calls.isEmpty()); // 온라인 포트(AccountSynthesisPort)는 한 번도 안 탄다
		assertEquals(1, batchUploads.size());
		assertEquals(1, batchCreated.size());
		assertEquals(1, result.processed());
		assertEquals(0, result.failed());

		String jsonl = new String(batchUploads.get(0), StandardCharsets.UTF_8);
		assertTrue(jsonl.contains("\"key\":\"acct_ad\""), jsonl);
		assertTrue(jsonl.contains("계정: @acct_ad"), jsonl);

		assertEquals(1L, db.queryForObject("SELECT count(*) FROM account_batch_jobs", Long.class));
		Map<String, Object> row = db.queryForMap("SELECT * FROM account_batch_jobs");
		assertEquals("pending", row.get("status"));
		assertEquals(1, row.get("submitted_count"));
		String sidecarJsonl = (String) row.get("sidecar_jsonl");
		assertTrue(sidecarJsonl != null && !sidecarJsonl.isBlank());
		assertTrue(sidecarJsonl.contains("last_posted_at"));
		assertTrue(sidecarJsonl.contains("analyzed_count"));
		assertTrue(sidecarJsonl.contains("ad_situation"));

		// 수거 전이므로 account_analyses는 아직 비어 있다(저장은 수거 시점)
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
	}

	@Test
	void 배치_전송이라도_batchApi가_없으면_온라인으로_폴백한다() {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.account-analyze-transport', 'batch')");
		rewireJob(fakePort(), null); // batchApi=null — 무료 gemini 폴백 상태 재현

		int processed = job.run().processed();

		assertEquals(5, processed);
		assertFalse(calls.isEmpty());
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM account_batch_jobs", Long.class));
		assertEquals(5L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
	}

	/**
	 * 제출 전 pending 잔여 수거(콘텐츠 submitBatch 동형) — 전날 미수거분을 이번 제출 전에 먼저
	 * 흡수해 account_batch_jobs가 pending으로 무한히 쌓이는 것을 완화한다.
	 */
	@Test
	void 배치_제출_전에_pending_잔여를_먼저_수거한다() {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.account-analyze-transport', 'batch')");
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.account-analyze-batch-limit', '1')");
		String sidecarJsonl = om.writeValueAsString(AccountBatchLines.sidecarLine(om, "prior_handle",
				OffsetDateTime.parse("2026-07-01T09:00:00+09:00"), 10L, AdSituation.COMPARABLE));
		db.update("""
				INSERT INTO account_batch_jobs (batch_name, submitted_count, status, sidecar_jsonl)
				VALUES ('batches/old', 1, 'pending', ?)""", sidecarJsonl);
		String copyJson = """
				{"tagline":"태그","traits":["성분 분석"],"perfSummary":"성과 요약",
				 "contentSummary":"콘텐츠 요약","adSummary":"광고 요약"}""";
		String resultJsonl = """
				{"key":"prior_handle","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(copyJson));
		rewireJob(fakePort(), sweepingBatchApi("files/old", resultJsonl));

		job.run();

		// 기존 pending 행은 수거되어 collected로 전이 + 저장됨
		assertEquals("collected", db.queryForObject(
				"SELECT status FROM account_batch_jobs WHERE batch_name = 'batches/old'", String.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'prior_handle'", Long.class));
		// 새 제출 행이 추가되어 총 2행(수거 대상 1 + 새 제출 1)
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM account_batch_jobs", Long.class));
		assertEquals("pending", db.queryForObject(
				"SELECT status FROM account_batch_jobs WHERE batch_name = 'batches/fake'", String.class));
	}
}
