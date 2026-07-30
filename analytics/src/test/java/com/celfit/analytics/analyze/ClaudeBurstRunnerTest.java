package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.testsupport.TestDb;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 구독 버스트 one-shot 계약 (07-19 — Gemini 무료 한도를 넘는 일회 물량을 Claude 구독 컴퓨트로 소화):
 * export(미분석 후보·카피 대상 → 프롬프트 JSONL·사이드카) / collect(드라이버 결과 파싱·sanitize →
 * 멱등 INSERT). LLM 호출 자체는 외부 드라이버(claude -p 병렬)가 담당 — 러너는 DB·프롬프트·저장만.
 */
@Testcontainers
class ClaudeBurstRunnerTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static final String INSIGHT_JSON = """
			{"detectedBrands":null,"sponsoredSignalLevel":"low","sponsoredSignalReasons":null,
			 "adDisclosure":"표기 없음","detectedProductCategories":["클렌징폼"],"detectedProducts":null,
			 "vlmAttributes":null,"mainCategory":"cleansing","subCategories":["클렌징폼"],
			 "detectedDistributors":null,"adType":"organic","isBeauty":true,
			 "aiContentSummary":"평균 수준","contentsPattern":"루틴형","aiCommentInsight":"표본 부족",
			 "commentAuthenticityGrade":"normal","commentAuthenticityNote":"근거"}"""
			.replace("\n", "");

	static final String COPY_JSON = """
			{"tagline":"저자극 스킨케어 리뷰","traits":["정보형 콘텐츠","솔직 리뷰"],
			 "perfSummary":"성과 요약 문장","contentSummary":"콘텐츠 요약 문장","adSummary":"광고 요약"}"""
			.replace("\n", "");

	@TempDir
	Path workDir;

	JdbcTemplate db;
	DataSource ds;
	ObjectMapper om = new ObjectMapper();

	ClaudeBurstRunner runner() {
		return new ClaudeBurstRunner(db, ds, new AnalyticsSettings(db),
				new BeautyTaxonomyLoader(ds), new com.celfit.analytics.llm.TraitTaxonomyLoader(ds), workDir);
	}

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		// 신 스키마 계약 픽스처 (GeminiBackfillRunnerTest 동일)
		db.update("CREATE SCHEMA analytics");
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.llm-model', 'claude-haiku-4-5-20251001')");
		db.update("""
				CREATE TABLE analytics.candidates_fixture (
				    short_code text PRIMARY KEY, content_type text, account_handle text,
				    uploaded_at timestamptz, caption text, thumbnail_url text, followers bigint,
				    views bigint, likes bigint, comments bigint, metric_captured_at timestamptz,
				    ad_marked boolean)""");
		db.update("CREATE VIEW analytics.v_analysis_candidates AS SELECT * FROM analytics.candidates_fixture");
		db.update("""
				CREATE TABLE analytics.baseline_fixture (
				    short_code text PRIMARY KEY, recent_reels_avg_views numeric, rank_in_recent_reels bigint,
				    recent_reels_count bigint, recent_contents_count bigint,
				    recent12_avg_engagement_rate numeric, recent12_avg_like_count numeric,
				    recent12_avg_comment_count numeric, category_top_percentile smallint,
				    category_avg_views numeric, category_sample_size bigint, captured_at timestamptz)""");
		db.update("CREATE VIEW analytics.v_analysis_baseline AS SELECT * FROM analytics.baseline_fixture");
		// 계정 평균 뷰(account_handle 키) — 최근창 밖 후보의 앵커 (실제로는 baseline의 계정 컬럼과 동치).
		db.update("""
				CREATE TABLE analytics.account_baseline_fixture (
				    account_handle text PRIMARY KEY, recent_reels_avg_views numeric,
				    recent_reels_count bigint, recent_contents_count bigint,
				    recent12_avg_engagement_rate numeric, recent12_avg_like_count numeric,
				    recent12_avg_comment_count numeric, category_top_percentile smallint,
				    category_avg_views numeric, category_sample_size bigint)""");
		db.update("CREATE VIEW analytics.v_analysis_account_baseline AS SELECT * FROM analytics.account_baseline_fixture");
		db.update("""
				INSERT INTO analytics.candidates_fixture VALUES
				  ('cb_a', 'reels', 'acct1', now() - interval '10 days', '캡션A', NULL, 10000, 9000, 500, 50, now()),
				  ('cb_b', 'feed', 'acct1', now() - interval '9 days', NULL, NULL, 10000, NULL, 300, 30, now()),
				  ('cb_done', 'reels', 'acct1', now() - interval '8 days', '이미 분석', NULL, 10000, 100, 10, 1, now()),
				  ('cb_out', 'reels', 'acct1', now() - interval '11 days', '캡션OUT', NULL, 10000, 6000, 400, 40, now())""");
		db.update("""
				INSERT INTO analytics.baseline_fixture VALUES
				  ('cb_a', 9000, 1, 2, 3, 0.0496, 940, 61, 67, 19333, 3, now()),
				  ('cb_b', NULL, NULL, 0, 3, 0.03, 500, 40, 90, 15000, 3, now()),
				  ('cb_done', 9000, 2, 2, 3, 0.04, 700, 50, 80, 19333, 3, now())""");
		// cb_out은 후보엔 있지만 콘텐츠 키 기준선엔 없음(최근창 밖) — 계정 평균만 붙는다
		db.update("""
				INSERT INTO analytics.account_baseline_fixture VALUES
				  ('acct1', 9000, 2, 3, 0.0496, 940, 61, 67, 19333, 3)""");
		db.update("""
				INSERT INTO content_analyses (short_code, model, ai_content_summary)
				VALUES ('cb_done', 'test', '기존 분석')""");

		// 카피 대상 픽스처: acct1(미카피 — 대상), acct_done(신 스키마 카피 완료 — 제외),
		// acct_legacy(07-27 개편 이전 구 스키마 행 — 입력 동일·쿨다운 미경과인데도 대상)
		// 신뢰도 판정 재료 9컬럼(V44)도 채워 "정상 미러됨" 상태를 흉내낸다 — 전부 NULL(데이터 미비)
		// 케이스는 별도 테스트(export는_데이터_미비_계정을_건너뛴다)에서만 재현한다.
		db.update("""
				INSERT INTO account_summaries (handle, analyzed_count, last_posted_at, organic_avg, ad_avg,
				  views_sample_count, likes_sample_count, comments_sample_count, reels_count, feed_count,
				  median_views, median_er_pct, top_views_share_pct, window_span_days)
				VALUES ('acct1', 3, timestamptz '2026-07-15 09:00:00+09', NULL, NULL,
				          3, 3, 3, 3, 0, 9000, 1.5, 60, 20),
				       ('acct_done', 2, timestamptz '2026-07-14 09:00:00+09', NULL, NULL,
				          2, 2, 2, 2, 0, 8000, 1.0, 55, 15),
				       ('acct_legacy', 2, timestamptz '2026-07-01 09:00:00+09', NULL, NULL,
				          2, 2, 2, 2, 0, 7000, 1.0, 50, 15)""");
		// copy_version을 CopyRules.VERSION으로 명시 — 기본값(0)이면 버전 게이트(설계 §4)에
		// 걸려 "카피 완료" 픽스처인데도 재대상으로 잡힌다.
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  tagline, perf_summary, content_summary, copy_version)
				VALUES ('acct_done', now(), 'test', timestamptz '2026-07-14 09:00:00+09',
				  't', '성과 요약', '콘텐츠 요약', ?)""",
				com.celfit.analytics.llm.CopyRules.VERSION);
		// 구 스키마 행: perf_summary NULL, 입력 동일(stale 아님)+쿨다운(기본 7일) 미경과(1시간 전)
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at, tagline, summary)
				VALUES ('acct_legacy', now() - interval '1 hour', 'test',
				  timestamptz '2026-07-01 09:00:00+09', '옛 태그라인', '옛 요약')""");
	}

	/**
	 * 버스트 export도 일상 배치와 같은 광고 정본(content_analyses.ad_type)을 쓴다 —
	 * 판정이 복제돼 한쪽만 고쳐지는 재발 방지(AccountAdCanon 공유).
	 * acct1은 미러 집계(organic_avg·ad_avg)가 NULL이지만 캡션 분류로는 협찬이 있다.
	 */
	@Test
	void export의_광고_비교_판정은_캡션_분류_정본을_따른다() throws Exception {
		db.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
				  views, likes, comments, sponsored) VALUES
				  ('cs1', 'acct1', timestamptz '2026-07-10 09:00:00+09', 'reels', 10000, 300, 30, false),
				  ('cs2', 'acct1', timestamptz '2026-07-15 09:00:00+09', 'reels',  6000, 200, 20, false)""");
		db.update("""
				INSERT INTO content_analyses (short_code, model, ad_type) VALUES
				  ('cs1', 'test', 'organic'), ('cs2', 'test', 'sponsored')""");

		runner().export();

		JsonNode sidecar = om.readTree(Files.readAllLines(workDir.resolve("accounts-sidecar.jsonl")).get(0));
		assertEquals("COMPARABLE", sidecar.path("ad_situation").asString());
		// 프롬프트 수치도 정본으로 치환된다 (metric 미지정 → likes 기준: organic 300 vs ad 200)
		String user = om.readTree(Files.readAllLines(workDir.resolve("accounts-input.jsonl")).get(0))
				.path("user").asString();
		assertTrue(user.contains("광고 활동: 비교 가능"), user);
		assertTrue(user.contains("organic_avg=300"), user);
		assertTrue(user.contains("ad_avg=200"), user);
	}

	@Test
	void export는_미분석_후보와_미카피_계정의_프롬프트_JSONL을_만든다() throws Exception {
		ClaudeBurstRunner.ExportResult result = runner().export();

		assertEquals(3, result.contents()); // cb_a, cb_b, cb_out — cb_done(분석됨) 제외
		// acct1(미카피) + acct_legacy(07-27 개편 이전 구 스키마 행 — 입력 동일해도 자연 재대상)
		// — acct_done(신 스키마 카피 완료)만 제외
		assertEquals(2, result.accounts());

		var contentLines = Files.readAllLines(workDir.resolve("contents-input.jsonl"));
		assertEquals(3, contentLines.size());
		JsonNode first = om.readTree(contentLines.get(0));
		assertEquals("cb_a", first.path("key").asString());
		assertTrue(first.path("system").asString().contains("[파트 B 절제 규칙 — 반드시 지켜라]"));
		assertTrue(first.path("user").asString().contains("캡션A"));
		assertTrue(first.path("user").asString().contains("JSON")); // 스키마 강제 문구(클로드는 response_schema 없음)
		assertTrue(Files.exists(workDir.resolve("contents-sidecar.jsonl")));

		// 최근창 밖 후보(cb_out) — 계정 평균은 앵커로 실리고 rank는 null (07-20 스코프 확장)
		JsonNode sidecarOut = null;
		for (String s : Files.readAllLines(workDir.resolve("contents-sidecar.jsonl"))) {
			JsonNode n = om.readTree(s);
			if (n.path("short_code").asString().equals("cb_out")) {
				sidecarOut = n;
			}
		}
		assertEquals("9000", sidecarOut.path("recent_reels_avg_views").asString()); // 계정 평균 앵커
		assertTrue(sidecarOut.path("rank_in_recent_reels").isNull()); // 최근창 밖 → rank 없음

		var accountLines = Files.readAllLines(workDir.resolve("accounts-input.jsonl"));
		JsonNode acct = om.readTree(accountLines.get(0));
		assertEquals("acct1", acct.path("key").asString());
		assertTrue(acct.path("system").asString().contains("tagline"));
		assertTrue(acct.path("user").asString().contains("@acct1"));
		JsonNode sidecar = om.readTree(Files.readAllLines(workDir.resolve("accounts-sidecar.jsonl")).get(0));
		assertEquals("acct1", sidecar.path("handle").asString());
		assertFalse(sidecar.path("input_last_posted_at").isNull());
	}

	/**
	 * 07-28 드리프트 재발 방지 — AccountAnalysisJob.ELIGIBLE_WHERE와 export가 같은 SQL을 써야 한다.
	 * acct_legacy는 입력 동일(stale 아님)·쿨다운(기본 7일) 미경과인데도 최신 행이 구 스키마
	 * (perf_summary NULL)라 export 대상에 잡혀야 한다(잡의 대상 정의와 재동형화 검증).
	 */
	@Test
	void export는_구_스키마_행만_있는_계정도_대상에_포함한다() throws Exception {
		runner().export();

		var accountLines = Files.readAllLines(workDir.resolve("accounts-input.jsonl"));
		List<String> keys = accountLines.stream()
				.map(l -> om.readTree(l).path("key").asString())
				.toList();
		assertTrue(keys.contains("acct_legacy"), keys.toString());
		assertFalse(keys.contains("acct_done"), keys.toString());
	}

	/**
	 * 배포 과도기 가드 — AccountAnalysisJob과 동일 취지(설계 §7). 뷰 선적용 없이 마이그레이션만
	 * 배포되면 미러가 9개 신뢰도 컬럼을 못 채운 채 남는데, 이 상태로 export하면 신뢰도 가드 없이
	 * 만든 문구가 CopyRules.VERSION으로 고정될 위험이 있다 — export 단계에서 걸러야 한다.
	 */
	@Test
	void export는_데이터_미비_계정을_건너뛴다() throws Exception {
		// 9컬럼을 아예 지정하지 않아 ADD COLUMN 기본값(NULL)인 "미러 갭" 상태를 그대로 재현한다.
		db.update("""
				INSERT INTO account_summaries (handle, analyzed_count, last_posted_at)
				VALUES ('acct_mirror_gap', 5, timestamptz '2026-07-16 09:00:00+09')""");

		ClaudeBurstRunner.ExportResult result = runner().export();

		var accountLines = Files.readAllLines(workDir.resolve("accounts-input.jsonl"));
		List<String> keys = accountLines.stream()
				.map(l -> om.readTree(l).path("key").asString())
				.toList();
		assertFalse(keys.contains("acct_mirror_gap"), keys.toString());
		// 나머지(acct1·acct_legacy)는 그대로 export된다 — 미비 계정만 걸린다.
		assertEquals(2, result.accounts());
	}

	@Test
	void collect는_결과를_sanitize해_저장하고_재실행에_멱등이다() throws Exception {
		runner().export();
		// 드라이버 산출 형태: {"key","text"} — 코드펜스가 섞여도 벗겨서 파싱한다
		Files.writeString(workDir.resolve("contents-results.jsonl"),
				om.createObjectNode().put("key", "cb_a").put("text", INSIGHT_JSON) + "\n"
				+ om.createObjectNode().put("key", "cb_b")
						.put("text", "```json\n" + INSIGHT_JSON + "\n```") + "\n");
		Files.writeString(workDir.resolve("accounts-results.jsonl"),
				om.createObjectNode().put("key", "acct1").put("text", COPY_JSON) + "\n");

		ClaudeBurstRunner.CollectResult result = runner().collect();

		assertEquals(2, result.contents());
		assertEquals(1, result.accounts());
		assertEquals("평균 수준", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'cb_a'", String.class));
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'cb_a'", String.class));
		assertEquals("claude-haiku-4-5-20251001", db.queryForObject(
				"SELECT model FROM content_analyses WHERE short_code = 'cb_a'", String.class));
		// 캡션 없는 cb_b는 속성 폐기 — 종합만 저장 (코드펜스 벗김 검증 겸)
		assertNull(db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'cb_b'", String.class));
		// 계정 카피: 스냅샷 북키핑(input_last_posted_at·analyzed_count)까지 기록돼야 재분석 stale 판정이 성립
		assertEquals("저자극 스킨케어 리뷰", db.queryForObject(
				"SELECT tagline FROM account_analyses WHERE handle = 'acct1'", String.class));
		assertEquals(3L, db.queryForObject(
				"SELECT input_analyzed_count FROM account_analyses WHERE handle = 'acct1'", Long.class));
		assertEquals("성과 요약 문장", db.queryForObject(
				"SELECT perf_summary FROM account_analyses WHERE handle = 'acct1'", String.class));
		assertEquals("콘텐츠 요약 문장", db.queryForObject(
				"SELECT content_summary FROM account_analyses WHERE handle = 'acct1'", String.class));
		// acct1은 account_content_series가 없어 측정 가능 게시물 자체가 없다 → AdSituation.INSUFFICIENT
		// (export가 사이드카에 적어둔 ad_situation 그대로) → adSummary는 값이 있어도 NULL로 버려진다
		assertNull(db.queryForObject(
				"SELECT ad_summary FROM account_analyses WHERE handle = 'acct1'", String.class));

		// 재실행 멱등 — 콘텐츠는 ON CONFLICT, 계정은 동일 입력 스냅샷 재수집 방지
		runner().collect();
		assertEquals(3L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
		// 픽스처(acct_done·acct_legacy) 2행 + 신규 acct1 카피 1행 — acct_legacy는 이번 세션의
		// export/collect 픽스처가 아니라 07-27 개편 이전 구 스키마 잔재 시늉이라 여기선 안 건드린다.
		assertEquals(3L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
	}

	@Test
	void 빈_종합이나_빈_카피는_저장하지_않는다() throws Exception {
		runner().export();
		String emptyInsight = INSIGHT_JSON.replace("\"aiContentSummary\":\"평균 수준\"",
				"\"aiContentSummary\":\"\"");
		String emptyCopy = COPY_JSON.replace("\"tagline\":\"저자극 스킨케어 리뷰\"", "\"tagline\":\"\"");
		Files.writeString(workDir.resolve("contents-results.jsonl"),
				om.createObjectNode().put("key", "cb_a").put("text", emptyInsight) + "\n");
		Files.writeString(workDir.resolve("accounts-results.jsonl"),
				om.createObjectNode().put("key", "acct1").put("text", emptyCopy) + "\n");

		ClaudeBurstRunner.CollectResult result = runner().collect();

		assertEquals(0, result.contents());
		assertEquals(0, result.accounts());
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'cb_a'", Long.class));
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'acct1'", Long.class));
	}
}
