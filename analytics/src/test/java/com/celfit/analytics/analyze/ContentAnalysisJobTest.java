package com.celfit.analytics.analyze;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.ContentInsightPort;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.Synthesis;
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
 * 콘텐츠 분석 배치 계약 케이스:
 * ① 미분석+분류완료 저장 ② 이미 분석 스킵 ③ 댓글 있는데 미분류 제외
 * ④ 속성 분석은 캡션 주·썸네일 보조(게이트 off·프리체크 실패여도 캡션으로 산출, 입력 전무면 속성 폐기)
 * ⑤ 한 콘텐츠 실패 격리 ⑥ B3 숙성 가드(게시 후 3일). 골격은 CommentClassificationJobTest 패턴 재사용.
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

	@BeforeEach
	void setUp() {
		ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		insightCalls = new ArrayList<>();
		thumbnailArgs = new ArrayList<>();
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

		// 분석 DB 시드: contents(미러) 3행 + content_comments·comment_classifications로 대상 조건 구성.
		// post_a: 댓글 있고 분류 완료 (대상 O), post_b: 댓글 없음 (대상 O), post_c: 댓글 있고 미분류 (대상 X)
		// metric_captured_at = 게시 +3.5일 — 제때(+pin 3일 근방) 크롤돼 고정 지표가 성립한 정상 케이스
		// metric_captured_at은 셋 다 제때창 [posted+3d, posted+5d) 안이되 서로 다르게 — 수집 최신순
		// 정렬(ORDER BY metric_captured_at DESC)이 post_b를 먼저 뽑는지 검증하기 위함 (b가 가장 최신).
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

	@Test
	void 게시_후_3일_미경과_콘텐츠는_대상에서_제외된다() {
		// B3 숙성 가드(07-14 확정): content_analyses는 불변·재분석 없음 — 게시 직후 분석되면
		// 덜 여문 지표·댓글로 영구 고정된다. 기본 3일 경과 후에만 분석.
		db.update("UPDATE contents SET posted_at = now() - interval '1 day' WHERE short_code = 'post_a'");

		int processed = job.run().processed();

		assertEquals(1, processed); // post_b만 (post_a는 숙성 미달, post_c는 미분류)
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		assertFalse(insightCalls.stream().anyMatch(c -> c.shortCode().equals("post_a")));
	}

	@Test
	void 숙성_일수는_app_setting으로_조정된다() {
		// 픽스처 게시일은 now()-10일 — 가드를 15일로 올리면 전부 미숙성이라 대상 없음
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-maturity-days', '15')");

		int processed = job.run().processed();

		assertEquals(0, processed);
	}

	@Test
	void posted_at이_NULL인_콘텐츠는_대상에서_제외된다() {
		// 게시일을 모르면 숙성 여부를 판정할 수 없다 — 실데이터엔 NULL 없음(140/140 확인, 2026-07-15)
		db.update("UPDATE contents SET posted_at = NULL WHERE short_code = 'post_a'");

		int processed = job.run().processed();

		assertEquals(1, processed); // post_b만
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
	}

	@Test
	void 분석_대상은_수집_최신순이다() {
		// 썸네일 서명 URL이 살아있을 때 VLM을 먼저 시도하기 위해 최신 수집분부터 처리한다.
		// LIMIT을 없앴으므로(전량 처리) 순서는 insightCalls 호출 순서로 검증한다.
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

		int processed = job.run().processed(); // 대상 2건(post_b→post_a 최신순) 중 두 번째에서 한도 소진

		assertEquals(1, processed);
		assertEquals(2, callCount.get()); // 중단 후 추가 콜 없음
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
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
	void 늦크롤_백필_게시물도_윈도우가_닫히면_runLateBackfill_대상에서도_제외된다() {
		// 07-20 개정으로 "백필 MVP 제외"(07-19)는 번복됐다 — 늦크롤이라도 계정별 최근 N개 윈도우
		// 안이면 이제 포함된다(late_backfill 마킹, 아래 늦크롤_최근_윈도우_안이면 테스트로 회귀 검증).
		// 이 테스트는 그 윈도우 경로까지 완전히 닫힌 경우(recent-window=0)를 고정한다 — 백필의
		// 킬스위치(recent-window=0으로 전량 차단)가 실제로 동작함을 runLateBackfill()로 검증한다.
		// (split 이후 recentWindow는 LATE_BACKFILL_SQL에만 쓰이므로 run()으로는 이 게이트를 못 태운다.)
		db.update("""
				UPDATE contents SET posted_at = now() - interval '20 days',
				  metric_captured_at = now() - interval '9 days' WHERE short_code = 'post_a'""");
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '0')");

		int timelyProcessed = job.run().processed();
		int backfillProcessed = job.runLateBackfill().processed();

		assertEquals(1, timelyProcessed); // post_b만 (post_a는 늦크롤)
		assertEquals(0, backfillProcessed); // post_a는 늦크롤이지만 윈도우가 닫혀 있어(rn<=0) 제외
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
	}

	@Test
	void 늦크롤이라도_최근_윈도우_안이면_runLateBackfill이_분석하고_late_backfill로_마킹한다() {
		// 07-20 PO 결정(스펙 2026-07-20-vertex-migration-recent12-backfill-design.md): 늦크롤(제때
		// 크롤 실패)이라도 계정의 최근 N개(기본 12) 윈도우 안이면 late_backfill 잡의 대상에 포함하고
		// V33 metric_timeliness를 late_backfill로 마킹한다. acct1은 총 3건뿐이라 기본 윈도우(12) 안.
		// timely·backfill 쿼리는 상호 배타적이므로(2026-07-23 설계) post_a는 runLateBackfill()에서만,
		// post_b는 run()에서만 나온다.
		db.update("""
				UPDATE contents SET posted_at = now() - interval '20 days',
				  metric_captured_at = now() - interval '9 days' WHERE short_code = 'post_a'""");

		int timelyProcessed = job.run().processed();
		int backfillProcessed = job.runLateBackfill().processed();

		assertEquals(1, timelyProcessed); // post_b만
		assertEquals(1, backfillProcessed); // post_a(늦크롤이지만 윈도우 안)
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
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
	void timely이면서_최근_윈도우_안인_콘텐츠는_runLateBackfill_쿼리에서_제외된다() {
		// 설계(2026-07-23): backfill 쿼리는 NOT timely를 명시한다. setUp 기본 픽스처의 post_a·post_b는
		// 둘 다 timely면서 동시에 최근 윈도우 안(acct1 총 3건 < 기본 윈도우 12)이지만, 이제 timely
		// 쪽이 먼저 가져가므로 runLateBackfill()에는 잡히지 않아야 한다 — 두 쿼리의 short_code 집합이
		// 항상 서로소여야 content_analyses INSERT 경합이 안 생긴다.
		int backfillProcessed = job.runLateBackfill().processed();

		assertEquals(0, backfillProcessed);
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void 늦크롤이면서_윈도우_밖이면_여전히_제외된다() {
		// 최근 윈도우를 1로 좁혀 acct1의 최신 게시물만 남긴다 — post_a를 늦크롤로 만들고 더 오래된
		// 게시물로 두면(post_b·post_c가 더 최근) rank가 윈도우 밖으로 밀려나 여전히 제외돼야 한다.
		db.update("""
				UPDATE contents SET posted_at = now() - interval '20 days',
				  metric_captured_at = now() - interval '9 days' WHERE short_code = 'post_a'""");
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '1')");

		int processed = job.run().processed();

		assertEquals(1, processed); // post_b만 (post_a는 늦크롤+윈도우 밖 모두 해당)
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
	}

	@Test
	void 늦크롤이면서_제때창이_아직_열려있으면_runLateBackfill에서도_제외된다() {
		// 최종 통합 리뷰 I-1: 제때창(posted_at + pin(3) + slack(2) = 기본 5일)이 아직 안 닫힌
		// 콘텐츠를 윈도우 경로로 조기 분석하면, 나중에 진짜 timely 스냅샷이 들어와도
		// content_analyses가 불변이라 late_backfill로 영구 오분류된다. 04 뷰의 "제때창이 완전히
		// 지난 날만 후보" 성숙 철학과 정렬하기 위해 late_backfill 쿼리에도 창 닫힘 게이트를 건다.
		// post_a: 숙성(3일)은 지났지만(4일 전 게시) pin+slack(5일)은 아직 안 지났다 — 창이
		// 열려 있는 상태. 지표도 미성숙(게시 +0.5일 캡처)이라 timely=false. acct1은 3건뿐이라
		// 기본 윈도우(12)에서 rank상으로는 포함 대상이지만, 창이 열려 있으니 제외돼야 한다.
		// post_a가 timely가 아니므로 run()으로는 이 게이트를 태우지 못한다 — runLateBackfill()로 검증.
		db.update("""
				UPDATE contents SET posted_at = now() - interval '4 days',
				  metric_captured_at = now() - interval '3 days 12 hours' WHERE short_code = 'post_a'""");

		int backfillProcessed = job.runLateBackfill().processed();

		assertEquals(0, backfillProcessed); // post_a는 창 안 닫힘, post_b는 애초 timely라 백필 대상 아님
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
	}

	@Test
	void 제때_크롤_판정_여유는_app_setting으로_조정된다() {
		// acct1은 3건뿐이라 기본 윈도우(12)에서 post_a가 항상 윈도우 경로로도 포함된다 — 슬랙
		// 로직이 사라져도 processed==2가 성립해 검증력이 없다(리뷰 지적). 윈도우를 0으로 닫아
		// 순수 슬랙 확장 효과만 검증하고, 슬랙 확장으로 포함된 건은 timely로 마킹됨도 함께 확인한다.
		db.update("""
				UPDATE contents SET posted_at = now() - interval '20 days',
				  metric_captured_at = now() - interval '9 days' WHERE short_code = 'post_a'""");
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-timely-slack-days', '30')");
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '0')");

		int processed = job.run().processed();

		assertEquals(2, processed); // 여유 30일이면 +11일 크롤분도 대상
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}

	@Test
	void 고정_지표가_미성숙_스냅샷이면_대상에서_제외된다() {
		// 숙성(3일) 게시물이어도 성숙 스냅샷이 아직 없으면 미러 지표는 미성숙 최신 폴백 —
		// 이대로 분석하면 덜 여문 지표로 영구 고정된다 (다음 크롤이 성숙 스냅샷을 잡으면 자연 재대상)
		// 최근 윈도우는 0으로 닫아 순수 제때 가드만 검증한다 — post_a는 방금 게시돼(4일 전) 그대로
		// 두면 계정 내 최신순위라 07-20 윈도우 OR 경로로 새어 들어간다(그 자체는 의도된 동작,
		// 아래 늦크롤이라도_최근_윈도우_안이면 테스트가 커버). 여기선 미성숙 가드 단독 배제를 고정한다.
		db.update("""
				UPDATE contents SET posted_at = now() - interval '4 days',
				  metric_captured_at = now() - interval '3 days 12 hours' WHERE short_code = 'post_a'""");
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '0')");

		int processed = job.run().processed();

		assertEquals(1, processed); // post_b만 (post_a 지표는 게시 +0.5일 시점)
	}

	@Test
	void 지표_수집_시각_미상_게시물은_대상에서_제외된다() {
		// metric_captured_at NULL이면 제때 크롤 여부를 판정할 수 없다 (posted_at NULL 케이스와 동일 규칙)
		// posted_at은 살아 있어 최근 윈도우 OR 경로로는 여전히 대상이 될 수 있으므로(의도된 동작),
		// 순수 제때 가드 단독 배제를 고정하기 위해 윈도우를 0으로 닫는다.
		db.update("UPDATE contents SET metric_captured_at = NULL WHERE short_code = 'post_a'");
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '0')");

		int processed = job.run().processed();

		assertEquals(1, processed); // post_b만
	}

	@Test
	void 진행률을_보고한다() {
		// 대상 1건으로 고정 — 최초 보고(대상 확정 직후)와 마지막 보고(처리 완료 직후)만 검증.
		// LIMIT이 없어졌으므로 post_b를 숙성 가드 미달로 만들어 대상에서 빼는 방식으로 1건을 고정한다.
		db.update("UPDATE contents SET posted_at = now() - interval '1 day' WHERE short_code = 'post_b'");
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
