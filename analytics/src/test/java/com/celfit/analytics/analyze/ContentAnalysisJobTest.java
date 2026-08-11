package com.celfit.analytics.analyze;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.ContentInsightPort;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.llm.Synthesis;
import com.celfit.analytics.testsupport.TestDb;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 콘텐츠 분석 배치 계약 케이스:
 * ① 미분석+분류완료 저장 ② 이미 분석 스킵 ③ 댓글 있는데 미분류 제외
 * ④ 속성 분석은 캡션 주·썸네일 보조(게이트 off·프리체크 실패여도 캡션으로 산출, 입력 전무면 속성 폐기)
 * ⑤ 한 콘텐츠 실패 격리 ⑥ 후보 자격은 raw 후보 뷰가 정본(07-28 캘린더일 정합) — 잡은 timely
 * 플래그 소비·마킹만. 골격은 CommentClassificationJobTest 패턴 재사용.
 * 포트는 ②속성+③종합 통합 1콜(ContentInsightPort — 07-18 확정).
 */
@Testcontainers
class ContentAnalysisJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	JdbcTemplate db;
	DataSource ds;
	ContentAnalysisJob job;
	List<ContentToAnalyze> insightCalls;
	List<String> thumbnailArgs; // 통합 콜에 전달된 thumbnailUrl (null = 캡션만)
	ObjectMapper om = new ObjectMapper();

	/** fake GeminiBatchApi — 배치 제출 경로 검증용. uploads/createdBatches에 호출 인자를 기록. */
	List<byte[]> batchUploads;
	List<String> batchCreated;

	GeminiBatchApi fakeBatchApi() {
		return new GeminiBatchApi() {
			@Override
			public String uploadFile(byte[] jsonl, String displayName) {
				batchUploads.add(jsonl);
				return "files/f1";
			}

			@Override
			public String createBatch(String model, String inputFileName, String displayName) {
				batchCreated.add(model + "|" + inputFileName);
				return "batches/b1";
			}

			@Override
			public String getBatch(String batchName) {
				// content_batch_jobs가 비어있으면(테스트 시작 상태) 제출 전 스윕이 조회하지 않는다 —
				// 그래도 방어적으로 실행 중 상태를 돌려줘 결과 다운로드까지 가지 않게 한다.
				return "{\"metadata\":{\"state\":\"JOB_STATE_RUNNING\"}}";
			}

			@Override
			public void downloadResults(String fileName, java.util.function.Consumer<String> onLine) {
				throw new IllegalStateException("제출 테스트에서는 호출되면 안 됨");
			}
		};
	}

	/** fake ContentInsightPort: 호출·썸네일 인자 기록 + 고정 응답(속성+종합 합본). */
	ContentInsightPort fakeInsightPort() {
		return (content, thumbnailUrl) -> {
			insightCalls.add(content);
			thumbnailArgs.add(thumbnailUrl);
			return new ContentInsightPort.ContentInsight(
					new ContentAttributes(List.of(new ContentAttributes.Brand("브랜드A", "화면 노출")), "high",
							List.of("협찬 표기 있음"), "표기 있음", List.of("클렌징폼"),
							List.of(new ContentAttributes.Product("딥클렌징폼", "브랜드A")),
							List.of(new ContentAttributes.Attribute("무드", "화사함")), "cleansing",
							List.of("클렌징폼/젤", "클렌징폼"), List.of("올리브영"), "sponsored", true),
					new Synthesis("요약: " + content.shortCode(), "패턴 해석", "댓글 인사이트", "high", "판정 근거"));
		};
	}

	void rewireJob(ContentInsightPort port, boolean thumbnailEnabled) {
		rewireJob(port, thumbnailEnabled, url -> true);
	}

	void rewireJob(ContentInsightPort port, boolean thumbnailEnabled, java.util.function.Predicate<String> thumbnailAlive) {
		job = new ContentAnalysisJob(db, ds, port, new AnalyticsSettings(db),
				thumbnailEnabled, thumbnailAlive, ProgressReporter.NOOP, ProgressReporter.NOOP);
	}

	/** 배치 전송 경로로 재배선 — batchApi=null이면 배치 미지원(온라인 폴백) 재현용. */
	void rewireJobWithBatch(ContentInsightPort port, GeminiBatchApi batchApi) {
		rewireJobWithBatch(port, batchApi, false);
	}

	void rewireJobWithBatch(ContentInsightPort port, GeminiBatchApi batchApi, boolean thumbnailEnabled) {
		job = new ContentAnalysisJob(db, ds, port, new AnalyticsSettings(db),
				thumbnailEnabled, url -> true, ProgressReporter.NOOP, ProgressReporter.NOOP,
				batchApi, new BeautyTaxonomyLoader(ds));
	}

	void enableBatchTransport() {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-transport', 'batch')");
	}

	/** insightCalls/thumbnailArgs 호출 순서를 단언하는 테스트 전용 — 병렬 처리(기본 concurrency=8)에서는
	 * 완료 순서가 섞이므로 concurrency=1로 고정해 순차 처리를 강제한다. */
	void pinSequentialConcurrency() {
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-concurrency', '1')");
	}

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		// 병렬 처리(기본 concurrency=8)로 여러 스레드가 동시에 add()할 수 있어 synchronizedList로
		// 감싼다 — 순서 결정성까지는 보장 안 하지만(그건 각 테스트가 필요시 concurrency=1로 고정),
		// 최소한 손실·손상 없이 안전하게 누적되게 한다 (2026-07-24 레이스 컨디션 수정).
		insightCalls = java.util.Collections.synchronizedList(new ArrayList<>());
		thumbnailArgs = java.util.Collections.synchronizedList(new ArrayList<>());
		batchUploads = new ArrayList<>();
		batchCreated = new ArrayList<>();
		// 테스트 간 완전 초기화: 스키마 통째 재생성 후 마이그레이션 재적용
		TestDb.resetAndMigrate(db, ds);

		// raw 대역: analytics.v_analysis_baseline과 같은 컬럼의 뷰 (고정 수치 테이블 기반).
		// 실제 뷰의 컬럼 타입(numeric/bigint/smallint 혼재)을 그대로 재현해 JDBC 매퍼(getBigDecimal)를 검증한다.
		db.update("CREATE SCHEMA analytics");
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");
		db.update("""
				CREATE TABLE analytics.baseline_fixture (
				    short_code                   text PRIMARY KEY,
				    recent_reels_avg_views       numeric,
				    rank_in_recent_reels         bigint,
				    recent_reels_count           bigint,
				    recent_contents_count        bigint,
				    recent12_avg_engagement_rate numeric,
				    recent12_avg_like_count      numeric,
				    recent12_avg_comment_count   numeric,
				    category_top_percentile      smallint,
				    category_avg_views           numeric,
				    category_sample_size         bigint,
				    captured_at                  timestamptz
				)""");
		db.update("""
				CREATE VIEW analytics.v_analysis_baseline AS SELECT * FROM analytics.baseline_fixture""");
		db.update("""
				INSERT INTO analytics.baseline_fixture VALUES
				  ('post_a', 9000, 1, 2, 3, 0.0496, 940, 61, 67, 19333, 3, timestamptz '2026-06-05 09:00:00+09'),
				  ('post_b', NULL, NULL, 0, 3, 0.03, 500, 40, 90, 15000, 3, timestamptz '2026-06-07 09:00:00+09'),
				  ('post_c', 9000, 2, 2, 3, 0.04, 700, 50, 80, 19333, 3, timestamptz '2026-06-06 09:00:00+09')""");

		// 계정 평균 뷰(account_handle 키, rank·captured_at 없음) — 최근창 밖 후보에 붙일 앵커 (07-20 스코프 확장).
		// short_code 기준선과 값이 달라야 폴백 경로가 계정 뷰를 쓴 것이 검증된다 (avg_views 8000 vs post_a 9000).
		db.update("""
				CREATE TABLE analytics.account_baseline_fixture (
				    account_handle               text PRIMARY KEY,
				    recent_reels_avg_views       numeric,
				    recent_reels_count           bigint,
				    recent_contents_count        bigint,
				    recent12_avg_engagement_rate numeric,
				    recent12_avg_like_count      numeric,
				    recent12_avg_comment_count   numeric,
				    category_top_percentile      smallint,
				    category_avg_views           numeric,
				    category_sample_size         bigint
				)""");
		db.update("""
				CREATE VIEW analytics.v_analysis_account_baseline AS SELECT * FROM analytics.account_baseline_fixture""");
		db.update("""
				INSERT INTO analytics.account_baseline_fixture VALUES
				  ('acct1', 8000, 2, 3, 0.045, 800, 55, NULL, NULL, NULL)""");

		// raw 대역: 후보 뷰(v_analysis_candidates)와 같은 소비 컬럼의 fixture 기반 뷰 —
		// 캘린더일 timely 판정·성숙·윈도우 게이트는 뷰 소관(SQL 하니스 04가 검증)이라
		// 잡 테스트는 뷰가 주는 (short_code, timely) 결과만 신뢰하고 소비한다 (07-28 정합).
		db.update("""
				CREATE TABLE analytics.candidates_fixture (
				    short_code         text PRIMARY KEY,
				    timely             boolean NOT NULL,
				    metric_captured_at timestamptz
				)""");
		db.update("""
				CREATE VIEW analytics.v_analysis_candidates AS SELECT * FROM analytics.candidates_fixture""");
		db.update("""
				INSERT INTO analytics.candidates_fixture VALUES
				  ('post_a', true, now() - interval '6 days 18 hours'),
				  ('post_b', true, now() - interval '6 days 6 hours'),
				  ('post_c', true, now() - interval '6 days 12 hours')""");

		// 분석 DB 시드: contents(미러) 3행 + content_comments·comment_classifications로 대상 조건 구성.
		// 후보 자격은 위 candidates_fixture(timely 컬럼)가 결정 — contents는 analyzeOne이 읽는 미러 대역.
		// post_a: 댓글 있고 분류 완료 (대상 O), post_b: 댓글 없음 (대상 O), post_c: 댓글 있고 미분류 (대상 X)
		// metric_captured_at은 fixture와 동일하게 유지 — 수집 최신순 정렬(ORDER BY metric_captured_at
		// DESC)이 post_b를 먼저 뽑는지 검증하기 위함 (b가 가장 최신).
		db.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, content_type, posted_at, metric_captured_at, views, likes, comments, ad_marked) VALUES
				  ('post_a', 'acct1', 'https://img/a.jpg', '캡션A', 'reels', now() - interval '10 days', now() - interval '6 days 18 hours', 11000, 520, 52, true),
				  ('post_b', 'acct1', 'https://img/b.jpg', '캡션B', 'feed', now() - interval '10 days', now() - interval '6 days 6 hours', NULL, 2000, 100, false),
				  ('post_c', 'acct1', 'https://img/c.jpg', '캡션C', 'reels', now() - interval '10 days', now() - interval '6 days 12 hours', 7000, 300, 30, false)""");
		db.update("""
				INSERT INTO content_comments (id, short_code, author_masked, body, like_count) VALUES
				  (1, 'post_a', 'aaa***', '어디서 사요?', 3),
				  (2, 'post_a', 'bbb***', '예뻐요', 1),
				  (3, 'post_c', 'ccc***', '좋아요', 0)""");
		db.update("""
				INSERT INTO comment_classifications (id, short_code, ai_category, model) VALUES
				  (1, 'post_a', 'purchase', 'claude-test'),
				  (2, 'post_a', 'positive', 'claude-test')""");

		job = new ContentAnalysisJob(db, ds, fakeInsightPort(), new AnalyticsSettings(db),
				false, url -> true, ProgressReporter.NOOP, ProgressReporter.NOOP);
	}

	@Test
	void 미분석_분류완료_콘텐츠가_분석되어_저장된다() {
		int processed = job.run().processed();

		assertEquals(2, processed); // post_a, post_b (post_c는 미분류라 제외)
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));

		assertEquals("요약: post_a", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertEquals(9000L, db.queryForObject(
				"SELECT recent_reels_avg_views FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		assertEquals(1, db.queryForObject(
				"SELECT rank_in_recent_reels FROM content_analyses WHERE short_code = 'post_a'", Integer.class));
		assertEquals(0, new java.math.BigDecimal("0.0496").compareTo(db.queryForObject(
				"SELECT recent12_avg_engagement_rate FROM content_analyses WHERE short_code = 'post_a'",
				java.math.BigDecimal.class)));
		assertEquals("high", db.queryForObject(
				"SELECT comment_authenticity_grade FROM content_analyses WHERE short_code = 'post_a'", String.class));
		// 데일리 잡 유입은 후보 뷰 가드가 제때 크롤을 보장 — timely 마킹(V33)
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_a'", String.class));

		// 종합 포트에 넘긴 댓글 분포가 comment_classifications 집계와 일치한다
		ContentToAnalyze callForA = insightCalls.stream()
				.filter(c -> c.shortCode().equals("post_a")).findFirst().orElseThrow();
		assertEquals(1L, callForA.commentCategoryCounts().get("purchase"));
		assertEquals(1L, callForA.commentCategoryCounts().get("positive"));
	}

	/**
	 * 인스타 유료 파트너십 태그(미러 contents.ad_marked)가 프롬프트 입력까지 전달돼야 한다 —
	 * 안 실으면 태그가 붙은 게시물을 LLM이 organic으로 뒤집는다(운영 실측 87건).
	 */
	@Test
	void 공식_광고태그가_프롬프트_입력으로_전달된다() {
		job.run();

		assertEquals(Boolean.TRUE, insightCalls.stream()
				.filter(c -> c.shortCode().equals("post_a")).findFirst().orElseThrow().adMarked());
		// post_b는 피드 — 미러가 false를 채운다 (post_c는 댓글 분류 미완이라 후보에서 제외)
		assertEquals(Boolean.FALSE, insightCalls.stream()
				.filter(c -> c.shortCode().equals("post_b")).findFirst().orElseThrow().adMarked());
	}

	@Test
	void 이미_분석된_콘텐츠는_건너뛴다() {
		job.run();
		insightCalls.clear();

		int processed = job.run().processed();

		assertEquals(0, processed);
		assertTrue(insightCalls.isEmpty());
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void 댓글_있는데_미분류인_콘텐츠는_대상에서_제외된다() {
		job.run();

		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_c'", Long.class));
		assertFalse(insightCalls.stream().anyMatch(c -> c.shortCode().equals("post_c")));
	}

	@Test
	void 썸네일_게이트_off여도_캡션_기반_속성이_저장된다() {
		int processed = job.run().processed(); // 기본 게이트: thumbnailEnabled=false

		assertEquals(2, processed);
		// 속성 콜은 항상 수행되되 썸네일은 미첨부(null) — 캡션 주 경로
		assertEquals(java.util.Arrays.asList(null, null), thumbnailArgs);
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertEquals("sponsored", db.queryForObject(
				"SELECT ad_type FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	@Test
	void 썸네일_게이트_on이면_생존_썸네일이_첨부되고_제품명까지_저장된다() {
		// thumbnailArgs 위치 동등성(수집 최신순)을 검증하므로 concurrency=1로 완료 순서를 고정한다.
		pinSequentialConcurrency();
		rewireJob(fakeInsightPort(), true);

		int processed = job.run().processed();

		assertEquals(2, processed);
		// 수집 최신순: post_b(06-07) → post_a(06-05), 둘 다 썸네일 생존 → URL 첨부
		assertEquals(List.of("https://img/b.jpg", "https://img/a.jpg"), thumbnailArgs);
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertEquals("[\"클렌징폼/젤\", \"클렌징폼\"]", db.queryForObject(
				"SELECT sub_categories::text FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertEquals("[\"올리브영\"]", db.queryForObject(
				"SELECT detected_distributors::text FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertEquals("[{\"name\": \"딥클렌징폼\", \"brand\": \"브랜드A\"}]", db.queryForObject(
				"SELECT detected_products::text FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertEquals("sponsored", db.queryForObject(
				"SELECT ad_type FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	@Test
	void 썸네일_프리체크_실패면_캡션만으로_속성을_산출한다() {
		// 만료된 서명 URL 재현: post_a 썸네일만 죽어 있다 — 구 VLM처럼 컬럼 NULL이 아니라 캡션 단독 분석으로 간다
		// thumbnailArgs 위치 동등성을 검증하므로 concurrency=1로 완료 순서를 고정한다.
		pinSequentialConcurrency();
		rewireJob(fakeInsightPort(), true, url -> url.equals("https://img/b.jpg"));

		int processed = job.run().processed();

		assertEquals(2, processed);
		// post_b는 썸네일 첨부, post_a는 캡션만(null)
		assertEquals(java.util.Arrays.asList("https://img/b.jpg", null), thumbnailArgs);
		assertEquals("요약: post_a", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	@Test
	void 캡션도_썸네일도_없으면_속성을_폐기하고_컬럼은_NULL이다() {
		// 입력이 아무것도 없는 콘텐츠 — 속성 근거가 없다. 통합 콜은 종합을 위해 1회 나가되
		// 속성 산출은 폐기해 컬럼 NULL 유지 (행 자체는 생성돼 배치 슬롯 잠식 방지)
		// thumbnailArgs 위치 동등성을 검증하므로 concurrency=1로 완료 순서를 고정한다.
		pinSequentialConcurrency();
		db.update("UPDATE contents SET caption = NULL, thumbnail_url = NULL WHERE short_code = 'post_a'");
		rewireJob(fakeInsightPort(), true);

		int processed = job.run().processed();

		assertEquals(2, processed);
		assertEquals(java.util.Arrays.asList("https://img/b.jpg", null), thumbnailArgs); // post_a도 통합 콜은 나간다
		assertNull(db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertNull(db.queryForObject(
				"SELECT detected_products FROM content_analyses WHERE short_code = 'post_a'", String.class));
		// 종합 텍스트는 정상 저장 — 행은 생성된다
		assertEquals("요약: post_a", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	// 성숙(창닫힘)·미성숙 스냅샷·posted_at/metric_captured_at NULL·윈도우·슬랙 게이트 테스트는
	// 07-28 캘린더일 정합으로 뷰(04) 소관이 되어 삭제 — SQL 하니스 04_analysis_candidates.test.sql이
	// 동일 케이스(dummy_op 창 미완료, rn 미성숙, recent-window=1·0, slack=2)를 커버한다.

	@Test
	void 분석_대상은_수집_최신순이다() {
		// 썸네일 서명 URL이 살아있을 때 VLM을 먼저 시도하기 위해 최신 수집분부터 처리한다.
		// LIMIT을 없앴으므로(전량 처리) 순서는 insightCalls 호출 순서로 검증한다.
		// 병렬 처리(기본 concurrency=8)에서는 완료 순서가 섞일 수 있어 concurrency=1로 고정해
		// 순서를 결정적으로 만든다 — 제출 순서(=최신순)는 병렬 여부와 무관하게 항상 유지된다.
		pinSequentialConcurrency();

		int processed = job.run().processed();

		assertEquals(2, processed);
		assertEquals("post_b", insightCalls.get(0).shortCode()); // captured_at 최신
		assertEquals("post_a", insightCalls.get(1).shortCode());
	}

	@Test
	void 최근창_밖_콘텐츠는_계정_평균을_앵커로_분석된다() {
		// 07-20 스코프 확장: 다작 계정의 최근창 밖 성숙분 재현 — contents엔 있고 제때 크롤됐지만
		// 콘텐츠 키 기준선(v_analysis_baseline)엔 없는 short_code. 예전엔 배치 슬롯 잠식 방지로 제외했으나,
		// 이제 계정 평균(v_analysis_account_baseline)을 앵커로 붙여 분석한다 (rank만 null).
		db.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, content_type, posted_at, metric_captured_at, views, likes, comments)
				VALUES ('post_0', 'acct1', 'https://img/0.jpg', '캡션0', 'reels', now() - interval '10 days', now() - interval '6 days 20 hours', 5000, 100, 10)""");
		db.update("""
				INSERT INTO analytics.candidates_fixture VALUES
				  ('post_0', true, now() - interval '6 days 20 hours')""");
		db.update("""
				INSERT INTO content_comments (id, short_code, author_masked, body, like_count)
				VALUES (10, 'post_0', 'ddd***', '굿', 0)""");
		db.update("""
				INSERT INTO comment_classifications (id, short_code, ai_category, model)
				VALUES (10, 'post_0', 'positive', 'claude-test')""");

		int processed = job.run().processed();

		assertEquals(3, processed); // post_a, post_b (최근창 안) + post_0 (최근창 밖, 계정 평균 앵커)
		assertTrue(insightCalls.stream().anyMatch(c -> c.shortCode().equals("post_0")));
		// 계정 평균이 저장된다 — short_code 기준선(post_a=9000)이 아닌 계정 뷰 값(8000), rank는 null
		assertEquals(8000L, db.queryForObject(
				"SELECT recent_reels_avg_views FROM content_analyses WHERE short_code = 'post_0'", Long.class));
		assertNull(db.queryForObject(
				"SELECT rank_in_recent_reels FROM content_analyses WHERE short_code = 'post_0'", Integer.class));
		// 프롬프트에도 계정 평균이 앵커로 실린다 (aiContentSummary의 '계정 평균 대비' 근거)
		ContentToAnalyze callFor0 = insightCalls.stream()
				.filter(c -> c.shortCode().equals("post_0")).findFirst().orElseThrow();
		assertEquals(8000L, ((Number) callFor0.baseline().get("recent_reels_avg_views")).longValue());
		assertNull(callFor0.baseline().get("rank_in_recent_reels"));
	}

	@Test
	void 빈_종합_텍스트는_저장하지_않고_다른_콘텐츠는_처리된다() {
		// 실전 스모크 재현: LLM이 텍스트 전부 빈 문자열인 Synthesis를 반환.
		// content_analyses는 불변이라 빈 결과가 저장되면 영구 고정 + 재분석 대상에서도 제외된다.
		rewireJob((content, thumbnailUrl) -> {
			insightCalls.add(content);
			Synthesis s = content.shortCode().equals("post_a")
					? new Synthesis("", "", "", "normal", "")
					: new Synthesis("요약: " + content.shortCode(), "패턴 해석", "댓글 인사이트", "normal", "근거");
			return new ContentInsightPort.ContentInsight(null, s);
		}, false);

		int processed = job.run().processed(); // 빈 결과는 실패 격리 경로로 skip — 예외가 전파되지 않아야 한다

		assertEquals(1, processed); // post_b만 성공
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_b'", Long.class));
	}

	@Test
	void 일_한도_소진이면_배치를_중단하고_잔여를_이월한다() {
		// 무료 티어 일 1,500콜 예산(07-18 확정) — 429 재시도 소진은 실패 카운트가 아니라 배치 중단(이월)
		java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
		ContentInsightPort delegate = fakeInsightPort();
		rewireJob((content, thumbnailUrl) -> {
			if (callCount.incrementAndGet() >= 2) {
				throw new com.celfit.analytics.llm.LlmQuotaExhaustedException("일 한도");
			}
			return delegate.analyze(content, thumbnailUrl);
		}, false);

		// 병렬화(2026-07-23) 후에도 대상 2건 중 하나가 먼저 카운트를 2로 올려 한도 소진 —
		// 어느 쪽이 먼저인지는 순서 무관(AtomicInteger 원자성만으로 결과가 결정적).
		int processed = job.run().processed();

		assertEquals(1, processed);
		assertEquals(2, callCount.get()); // 중단 후 추가 콜 없음
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void 쿼타_소진_플래그가_서면_이후_대상은_LLM_호출_없이_스킵된다() {
		// 병렬화(2026-07-23) 후에도 쿼타 소진 후 남은 큐가 추가로 429를 만들며 시간을 낭비하지
		// 않아야 한다. concurrency=1로 고정해 순서를 결정적으로 만들고, 최신순 첫 대상(post_b)에서
		// 소진시켜 나머지(post_a, post_0)가 insight.analyze() 자체를 안 타는지 확인한다.
		pinSequentialConcurrency();
		db.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, content_type, posted_at, metric_captured_at, views, likes, comments)
				VALUES ('post_0', 'acct1', 'https://img/0.jpg', '캡션0', 'reels', now() - interval '10 days', now() - interval '6 days 22 hours', 5000, 100, 10)""");
		db.update("""
				INSERT INTO analytics.candidates_fixture VALUES
				  ('post_0', true, now() - interval '6 days 22 hours')""");
		db.update("""
				INSERT INTO content_comments (id, short_code, author_masked, body, like_count)
				VALUES (10, 'post_0', 'ddd***', '굿', 0)""");
		db.update("""
				INSERT INTO comment_classifications (id, short_code, ai_category, model)
				VALUES (10, 'post_0', 'positive', 'claude-test')""");
		List<String> attempted = new ArrayList<>();
		rewireJob((content, thumbnailUrl) -> {
			attempted.add(content.shortCode());
			throw new com.celfit.analytics.llm.LlmQuotaExhaustedException("일 한도");
		}, false);

		int processed = job.run().processed();

		assertEquals(0, processed);
		assertEquals(1, attempted.size()); // 최신순 첫 대상(post_b)에서 소진 — 나머지 2건은 호출 자체가 없음
		assertEquals("post_b", attempted.get(0));
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void 프로바이더_기본은_gemini고_app_setting으로_롤백된다() {
		AnalyticsSettings settings = new AnalyticsSettings(db);
		assertEquals("gemini", settings.llmProvider());
		assertEquals("gemini-3.1-flash-lite", settings.geminiModel());
		assertEquals(15, settings.geminiRpm());
		assertEquals("gemini-3.1-flash-lite", settings.activeLlmModel());

		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.llm-provider', 'anthropic')");
		assertEquals("claude-haiku-4-5-20251001", settings.activeLlmModel()); // 롤백 시 anthropic 모델 기록 — 폴백 기본은 haiku(비용 가드)
	}

	@Test
	void 동시_처리_개수_기본값과_app_setting_오버라이드() {
		AnalyticsSettings settings = new AnalyticsSettings(db);
		assertEquals(8, settings.analyzeConcurrency());

		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-concurrency', '3')");
		assertEquals(3, settings.analyzeConcurrency());
	}

	@Test
	void 전송_방식_기본은_online이고_app_setting으로_batch_전환된다() {
		AnalyticsSettings settings = new AnalyticsSettings(db);
		assertEquals("online", settings.analyzeTransport());

		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-transport', 'batch')");
		assertEquals("batch", settings.analyzeTransport());
	}

	@Test
	void 전송_방식_batch면_온라인_호출_없이_배치로_제출되고_pending_행이_기록된다() {
		enableBatchTransport();
		rewireJobWithBatch(fakeInsightPort(), fakeBatchApi());

		JobResult result = job.run();

		assertTrue(insightCalls.isEmpty()); // ContentInsightPort(온라인 콜)는 한 번도 안 탄다
		assertEquals(1, batchUploads.size());
		assertEquals(1, batchCreated.size());
		assertEquals(2, result.processed()); // post_a·post_b 제출 — post_c는 댓글 미분류 게이트로 제외
		assertEquals(0, result.failed());

		assertEquals(1L, db.queryForObject("SELECT count(*) FROM content_batch_jobs", Long.class));
		Map<String, Object> row = db.queryForMap("SELECT * FROM content_batch_jobs");
		assertEquals("batches/b1", row.get("batch_name"));
		assertEquals(Boolean.TRUE, row.get("timely"));
		assertEquals(2, row.get("submitted_count"));
		assertEquals("pending", row.get("status"));
		// 사이드카는 로컬 파일이 아니라 DB 컬럼에 저장된다(analytics 컨테이너 무볼륨 대응, 2026-08-11
		// 리뷰 반영) — post_a·post_b 두 short_code 행이 JSONL 2줄로 실려 있어야 한다.
		String sidecarJsonl = (String) row.get("sidecar_jsonl");
		assertTrue(sidecarJsonl != null && !sidecarJsonl.isBlank());
		assertTrue(sidecarJsonl.contains("\"post_a\""));
		assertTrue(sidecarJsonl.contains("\"post_b\""));
		// 수거 전이므로 content_analyses는 아직 비어 있다
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void 배치_제출도_3종_제외_게이트가_동일_적용된다() {
		enableBatchTransport();
		rewireJobWithBatch(fakeInsightPort(), fakeBatchApi());

		job.run();

		String jsonl = new String(batchUploads.get(0), StandardCharsets.UTF_8);
		List<String> keys = new ArrayList<>();
		for (String line : jsonl.strip().split("\n")) {
			keys.add(om.readTree(line).path("key").asString());
		}
		// post_c(댓글 있는데 미분류)는 온라인 경로와 동일하게 제외 — 수집 최신순은 post_b, post_a
		assertEquals(List.of("post_b", "post_a"), keys);
	}

	@Test
	void 배치_제출_JSONL은_댓글_분류_분포를_실제로_싣는다() {
		// 온라인 경로(analyzeOne)와 동일하게 shortCode별 comment_classifications 집계를 실어야
		// 프롬프트의 aiCommentInsight 근거가 온라인·배치 양쪽에서 갈리지 않는다(2026-08-11 리뷰 반영
		// 전에는 GeminiBatchLines.requestLine 내부가 항상 Map.of()로 비워 보내고 있었다).
		enableBatchTransport();
		rewireJobWithBatch(fakeInsightPort(), fakeBatchApi());

		job.run();

		String jsonl = new String(batchUploads.get(0), StandardCharsets.UTF_8);
		String[] lines = jsonl.strip().split("\n");
		// 수집 최신순: post_b(댓글 없음) → post_a(purchase 1건·positive 1건, setUp 픽스처)
		String postALine = lines[1];
		JsonNode postA = om.readTree(postALine);
		assertEquals("post_a", postA.path("key").asString());
		String userText = postA.path("request").path("contents").get(0).path("parts").get(0).path("text").asString();
		assertTrue(userText.contains("purchase=1"));
		assertTrue(userText.contains("positive=1"));
		// 댓글이 없는 post_b는 빈 분포({})가 정상 — 게이트 통과 대상이라 조회 자체는 동일하게 수행된다
		JsonNode postB = om.readTree(lines[0]);
		assertEquals("post_b", postB.path("key").asString());
		String postBText = postB.path("request").path("contents").get(0).path("parts").get(0).path("text").asString();
		assertTrue(postBText.contains("댓글 분류 분포: {}"));
	}

	@Test
	void late_backfill도_배치_전송이_적용되고_timely_false로_기록된다() {
		db.update("UPDATE analytics.candidates_fixture SET timely = false WHERE short_code = 'post_a'");
		enableBatchTransport();
		rewireJobWithBatch(fakeInsightPort(), fakeBatchApi());

		JobResult result = job.runLateBackfill();

		assertEquals(1, result.processed()); // post_a만(post_b는 여전히 timely=true라 이 진입점 대상이 아님)
		Map<String, Object> row = db.queryForMap("SELECT * FROM content_batch_jobs");
		assertEquals(Boolean.FALSE, row.get("timely"));
	}

	@Test
	void 배치_전송_설정인데_GeminiApi가_배치_미지원이면_온라인으로_폴백한다() {
		enableBatchTransport();
		rewireJobWithBatch(fakeInsightPort(), null); // batchApi=null — 무료 gemini 폴백 상태 재현

		int processed = job.run().processed();

		assertEquals(2, processed);
		assertEquals(2, insightCalls.size()); // 온라인 경로가 정상 동작
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_batch_jobs", Long.class));
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void vlm_enabled인데_배치_전송이면_온라인으로_폴백해_썸네일이_보존된다() {
		// 배치 JSONL은 캡션 전용(썸네일 미첨부)이라 vlm-enabled=true와 양립하지 않는다 — 배치로
		// 내려가면 조용히 이미지 없이 분석돼 온라인과 산출물이 갈리므로, batchApi가 정상이어도
		// 온라인으로 폴백해 멀티모달 분석을 보존해야 한다(2026-08-11 리뷰 반영).
		enableBatchTransport();
		pinSequentialConcurrency();
		rewireJobWithBatch(fakeInsightPort(), fakeBatchApi(), true);

		int processed = job.run().processed();

		assertEquals(2, processed);
		assertEquals(2, insightCalls.size()); // 온라인 경로가 정상 동작
		assertEquals(0, batchUploads.size()); // 배치 제출은 시도되지 않는다
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_batch_jobs", Long.class));
		assertEquals(2L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
		// 온라인 경로이므로 썸네일 게이트가 살아있어 생존 썸네일이 실제로 첨부된다(회귀 방지)
		assertEquals(List.of("https://img/b.jpg", "https://img/a.jpg"), thumbnailArgs);
	}

	@Test
	void 콘텐츠_하나가_실패해도_나머지는_처리된다() {
		// 포트 대역: post_a만 예외 (모의 LLM 장애)
		rewireJob((content, thumbnailUrl) -> {
			if (content.shortCode().equals("post_a")) {
				throw new IllegalStateException("모의 LLM 장애");
			}
			insightCalls.add(content);
			return new ContentInsightPort.ContentInsight(null,
					new Synthesis("요약: " + content.shortCode(), "패턴 해석", "댓글 인사이트", "normal", "근거"));
		}, false);

		int processed = job.run().processed(); // 예외가 전파되지 않아야 한다

		assertEquals(1, processed); // post_b만 성공
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_b'", Long.class));
	}

	@Test
	void 뷰가_NOT_timely로_준_후보는_runLateBackfill이_분석하고_late_backfill로_마킹한다() {
		// 07-28 정합: 늦크롤 여부는 뷰의 timely 컬럼이 정본 — 잡은 플래그를 그대로 소비해 마킹한다.
		// timely·backfill 진입점은 WHERE timely = ? 로 상호 배타(같은 뷰의 서로소 분할).
		db.update("UPDATE analytics.candidates_fixture SET timely = false WHERE short_code = 'post_a'");

		int timelyProcessed = job.run().processed();
		int backfillProcessed = job.runLateBackfill().processed();

		assertEquals(1, timelyProcessed); // post_b만
		assertEquals(1, backfillProcessed); // post_a
		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	@Test
	void 제때_크롤분은_timely로_마킹한다() {
		// 회귀: 제때 가드를 충족하는 기존 경로는 여전히 timely로 마킹된다 (post_a는 setUp 기본값 그대로).
		int processed = job.run().processed();

		assertEquals(2, processed);
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	@Test
	void timely_후보는_runLateBackfill_대상이_아니다() {
		// setUp 기본 fixture는 전부 timely=true — backfill 진입점은 아무것도 집지 않아야
		// 두 진입점의 short_code 집합이 서로소가 되고 content_analyses INSERT 경합이 없다.
		int backfillProcessed = job.runLateBackfill().processed();

		assertEquals(0, backfillProcessed);
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void 후보가_미러에_없으면_스킵하고_실패로_세지_않는다() {
		// 라이브 뷰(후보)와 미러(19:30 스냅샷) 사이 간극 가드 — analyzeOne이 미러에서 행을
		// 못 찾아 실패 카운트를 오염시키는 대신 대상에서 조용히 빠지고, 다음 미러 후 자연 재대상.
		db.update("""
				INSERT INTO analytics.candidates_fixture VALUES
				  ('post_ghost', true, now() - interval '1 hour')""");

		var result = job.run();

		assertEquals(2, result.processed()); // post_a·post_b — post_ghost는 스킵
		assertEquals(0, result.failed());
		assertFalse(insightCalls.stream().anyMatch(c -> c.shortCode().equals("post_ghost")));
	}

	@Test
	void 진행률을_보고한다() {
		// 대상 1건으로 고정 — 최초 보고(대상 확정 직후)와 마지막 보고(처리 완료 직후)만 검증.
		// LIMIT이 없어졌으므로 post_b를 후보 fixture에서 제거하는 방식으로 1건을 고정한다.
		db.update("DELETE FROM analytics.candidates_fixture WHERE short_code = 'post_b'");
		List<int[]> reports = new ArrayList<>();
		ProgressReporter reporter = (p, f, t) -> reports.add(new int[]{p, f, t});
		job = new ContentAnalysisJob(db, ds, fakeInsightPort(), new AnalyticsSettings(db),
				false, url -> true, reporter, ProgressReporter.NOOP);

		job.run();

		assertThat(reports.getFirst()).containsExactly(0, 0, 1);
		assertThat(reports.getLast()).containsExactly(1, 0, 1);
	}

	@Test
	void 비뷰티_콘텐츠는_is_beauty_false로_저장되고_재분석_루프에_안_빠진다() {
		// isBeauty=false + mainCategory=null(비뷰티라 자연 null) — 행은 기록되되 서빙에서 제외될 값
		rewireJob((content, thumbnailUrl) -> {
			insightCalls.add(content);
			ContentAttributes nonBeauty = new ContentAttributes(List.of(), null, List.of(), "표기 없음",
					List.of(), List.of(), List.of(), null, List.of(), List.of(), "organic", false);
			return new ContentInsightPort.ContentInsight(nonBeauty,
					new Synthesis("요약: " + content.shortCode(), "패턴", "인사이트", "normal", "근거"));
		}, false);

		int processed = job.run().processed();

		assertEquals(2, processed); // post_a·post_b 모두 저장(비뷰티도 행 생성)
		assertEquals(Boolean.FALSE, db.queryForObject(
				"SELECT is_beauty FROM content_analyses WHERE short_code = 'post_a'", Boolean.class));
		assertNull(db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	@Test
	void 뷰티지만_대분류_미도출이면_is_beauty_false로_종결_저장한다() {
		// isBeauty=true인데 복구 후에도 mainCategory=null인 케이스. 분석은 temperature 0 결정론이라
		// 같은 입력을 재실행해도 동일 결과 → 옛 self-heal(행 미기록→재대상)은 매 실행 무한 재시도로
		// 영영 완료되지 않고 호출만 태웠다. 이제 is_beauty=false로 **종결 저장**해 루프를 끊는다.
		// 불변식('main_category null ⇒ 서빙에서 비뷰티') 보존: is_beauty=false라 랭킹·상세에서 제외되고,
		// 서빙 계층 무변경. (재대상 폴백은 빈 종합/파싱 오류 같은 진짜 일시 실패에만 남긴다.)
		rewireJob((content, thumbnailUrl) -> {
			insightCalls.add(content);
			ContentAttributes beautyNoCat = new ContentAttributes(List.of(), null, List.of(), "표기 없음",
					List.of(), List.of(), List.of(), null, List.of(), List.of(), "organic", true);
			Synthesis s = new Synthesis("요약: " + content.shortCode(), "패턴", "인사이트", "normal", "근거");
			ContentAttributes attrs = content.shortCode().equals("post_a") ? beautyNoCat
					: new ContentAttributes(List.of(), null, List.of(), "표기 없음", List.of(), List.of(),
							List.of(), "makeup", List.of(), List.of(), "organic", true); // post_b는 정상
			return new ContentInsightPort.ContentInsight(attrs, s);
		}, false);

		int processed = job.run().processed();

		assertEquals(2, processed); // post_a·post_b 모두 종결 저장(더 이상 skip 아님)
		// post_a: 뷰티였으나 대분류 미도출 → is_beauty=false로 저장, main_category는 null 유지
		assertEquals(Boolean.FALSE, db.queryForObject(
				"SELECT is_beauty FROM content_analyses WHERE short_code = 'post_a'", Boolean.class));
		assertNull(db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'post_a'", String.class));
		// 종합 텍스트는 정상 저장돼 행이 존재 → NOT EXISTS로 다음 실행 재대상 안 됨(루프 종료)
		assertEquals("요약: post_a", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'post_a'", String.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_b'", Long.class));
	}

	@Test
	void 정상_뷰티_콘텐츠는_is_beauty_true로_저장된다() {
		// 기존 fakeInsightPort는 isBeauty=true·mainCategory=cleansing
		job.run();
		assertEquals(Boolean.TRUE, db.queryForObject(
				"SELECT is_beauty FROM content_analyses WHERE short_code = 'post_a'", Boolean.class));
	}
}
