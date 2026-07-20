package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.testsupport.TestDb;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 백필 one-shot 계약: submit(미분석 후보 → JSONL·사이드카·배치 생성) /
 * collect(결과 파싱·sanitize → ON CONFLICT 멱등 INSERT). 뷰는 신 스키마 계약(04·03) 픽스처.
 */
@Testcontainers
class GeminiBackfillRunnerTest {

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

	@TempDir
	Path workDir;

	JdbcTemplate db;
	DataSource ds;
	ObjectMapper om = new ObjectMapper();
	List<byte[]> uploads = new java.util.ArrayList<>();
	List<String> createdBatches = new java.util.ArrayList<>();
	String resultJsonl;

	GeminiBatchApi fakeApi() {
		return new GeminiBatchApi() {
			@Override
			public String uploadFile(byte[] jsonl, String displayName) {
				uploads.add(jsonl);
				return "files/f1";
			}

			@Override
			public String createBatch(String model, String inputFileName, String displayName) {
				createdBatches.add(model + "|" + inputFileName);
				return "batches/b1";
			}

			@Override
			public String getBatch(String batchName) {
				return """
						{"name":"batches/b1","metadata":{"state":"JOB_STATE_SUCCEEDED",
						 "output":{"responsesFile":"files/r1"}}}""";
			}

			@Override
			public String downloadFile(String fileName) {
				return resultJsonl;
			}
		};
	}

	GeminiBackfillRunner runner() {
		// 어휘는 실물 — resetAndMigrate가 V30 시드(cleansing·클렌징폼 포함)를 심는다
		return new GeminiBackfillRunner(db, ds, fakeApi(), new AnalyticsSettings(db),
				new BeautyTaxonomyLoader(ds), workDir);
	}

	@BeforeEach
	void setUp() {
		ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);

		// 신 스키마 계약 픽스처: v_analysis_candidates(04) + v_analysis_baseline(03) — 문서화된 컬럼 그대로
		db.update("CREATE SCHEMA analytics");
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");
		db.update("""
				CREATE TABLE analytics.candidates_fixture (
				    short_code text PRIMARY KEY, content_type text, account_handle text,
				    uploaded_at timestamptz, caption text, thumbnail_url text, followers bigint,
				    views bigint, likes bigint, comments bigint, metric_captured_at timestamptz,
				    timely boolean)""");
		db.update("CREATE VIEW analytics.v_analysis_candidates AS SELECT * FROM analytics.candidates_fixture");
		db.update("""
				CREATE TABLE analytics.baseline_fixture (
				    short_code text PRIMARY KEY, recent_reels_avg_views numeric, rank_in_recent_reels bigint,
				    recent_reels_count bigint, recent_contents_count bigint,
				    recent12_avg_engagement_rate numeric, recent12_avg_like_count numeric,
				    recent12_avg_comment_count numeric, category_top_percentile smallint,
				    category_avg_views numeric, category_sample_size bigint, captured_at timestamptz)""");
		db.update("CREATE VIEW analytics.v_analysis_baseline AS SELECT * FROM analytics.baseline_fixture");
		// 계정 평균 뷰(account_handle 키) — 실제로는 v_analysis_baseline의 계정 컬럼과 동치. 최근창 밖 후보의 앵커.
		db.update("""
				CREATE TABLE analytics.account_baseline_fixture (
				    account_handle text PRIMARY KEY, recent_reels_avg_views numeric,
				    recent_reels_count bigint, recent_contents_count bigint,
				    recent12_avg_engagement_rate numeric, recent12_avg_like_count numeric,
				    recent12_avg_comment_count numeric, category_top_percentile smallint,
				    category_avg_views numeric, category_sample_size bigint)""");
		db.update("CREATE VIEW analytics.v_analysis_account_baseline AS SELECT * FROM analytics.account_baseline_fixture");
		// timely: bf_a=제때 크롤(true), bf_b=윈도우 경로로만 들어온 늦크롤(false), bf_out=최근창 밖
		// 성숙분(제때 크롤 true) — V33 마킹 분기(#85)와 계정 평균 앵커(#79 재통합)를 동시 검증한다.
		db.update("""
				INSERT INTO analytics.candidates_fixture VALUES
				  ('bf_a', 'reels', 'acct1', now() - interval '10 days', '캡션A', NULL, 10000, 9000, 500, 50, now(), true),
				  ('bf_b', 'feed', 'acct1', now() - interval '9 days', NULL, NULL, 10000, NULL, 300, 30, now(), false),
				  ('bf_done', 'reels', 'acct1', now() - interval '8 days', '이미 분석', NULL, 10000, 100, 10, 1, now(), true),
				  ('bf_out', 'reels', 'acct1', now() - interval '11 days', '캡션OUT', NULL, 10000, 6000, 400, 40, now(), true)""");
		db.update("""
				INSERT INTO analytics.baseline_fixture VALUES
				  ('bf_a', 9000, 1, 2, 3, 0.0496, 940, 61, 67, 19333, 3, now()),
				  ('bf_b', NULL, NULL, 0, 3, 0.03, 500, 40, 90, 15000, 3, now()),
				  ('bf_done', 9000, 2, 2, 3, 0.04, 700, 50, 80, 19333, 3, now())""");
		// bf_out은 후보엔 있지만 콘텐츠 키 기준선엔 없음(최근창 밖) — 계정 평균만 붙는다
		db.update("""
				INSERT INTO analytics.account_baseline_fixture VALUES
				  ('acct1', 9000, 2, 3, 0.0496, 940, 61, 67, 19333, 3)""");
		// bf_done은 이미 분석됨 — submit 대상에서 제외돼야 한다
		db.update("""
				INSERT INTO content_analyses (short_code, model, ai_content_summary)
				VALUES ('bf_done', 'test', '기존 분석')""");
	}

	@Test
	void submit은_미분석_후보만_JSONL로_만들고_배치를_생성한다() throws Exception {
		String batchName = runner().submit();

		assertEquals("batches/b1", batchName);
		assertEquals(1, uploads.size());
		String jsonl = new String(uploads.get(0), StandardCharsets.UTF_8);
		String[] lines = jsonl.strip().split("\n");
		assertEquals(3, lines.length); // bf_a, bf_b, bf_out — bf_done(분석됨) 제외
		JsonNode first = om.readTree(lines[0]);
		assertEquals("bf_a", first.path("key").asString());
		assertTrue(first.path("request").path("generationConfig").path("responseSchema").has("type"));
		assertTrue(first.path("request").path("systemInstruction").path("parts").get(0)
				.path("text").asString().contains("[파트 B 절제 규칙 — 반드시 지켜라]"));
		assertTrue(first.path("request").path("contents").get(0).path("parts").get(0)
				.path("text").asString().contains("캡션A"));
		// 모델은 설정 기본값, 입력 파일은 업로드 결과
		assertEquals(List.of("gemini-3.1-flash-lite|files/f1"), createdBatches);
		assertTrue(Files.exists(workDir.resolve("backfill-sidecar.jsonl")));

		// 최근창 밖 후보(bf_out) — 계정 평균은 앵커로 실리고 rank는 null (07-20 스코프 확장)
		JsonNode sidecarOut = null;
		for (String s : Files.readAllLines(workDir.resolve("backfill-sidecar.jsonl"))) {
			JsonNode n = om.readTree(s);
			if (n.path("short_code").asString().equals("bf_out")) {
				sidecarOut = n;
			}
		}
		assertEquals("9000", sidecarOut.path("recent_reels_avg_views").asString()); // 계정 평균 앵커
		assertTrue(sidecarOut.path("rank_in_recent_reels").isNull()); // 최근창 밖 → rank 없음
	}

	@Test
	void collect는_결과를_sanitize해_저장하고_재실행에_멱등이다() {
		runner().submit();
		resultJsonl = """
				{"key":"bf_a","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}
				{"key":"bf_b","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(INSIGHT_JSON), om.writeValueAsString(INSIGHT_JSON));

		int saved = runner().collect("batches/b1");

		assertEquals(2, saved);
		assertEquals("평균 수준", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'bf_a'", String.class));
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'bf_a'", String.class));
		assertEquals(9000L, db.queryForObject(
				"SELECT recent_reels_avg_views FROM content_analyses WHERE short_code = 'bf_a'", Long.class));
		assertEquals("gemini-3.1-flash-lite", db.queryForObject(
				"SELECT model FROM content_analyses WHERE short_code = 'bf_a'", String.class));
		// 캡션 없는 bf_b는 속성 폐기 — 종합만 저장
		assertNull(db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'bf_b'", String.class));
		assertEquals("평균 수준", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'bf_b'", String.class));
		// 마킹은 사이드카에 실린 뷰의 timely 판정을 승계(07-20 개정) — bf_a=timely, bf_b=late_backfill
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'bf_a'", String.class));
		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'bf_b'", String.class));

		// 재실행 멱등 — ON CONFLICT DO NOTHING이라 행 수·내용 불변
		runner().collect("batches/b1");
		assertEquals(3L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void collect는_구버전_사이드카에_timely_키가_없으면_late_backfill로_폴백한다() throws java.io.IOException {
		// timely 컬럼 도입(07-20) 이전에 만들어진 사이드카를 흉내 — timely 키가 아예 없다
		tools.jackson.databind.node.ObjectNode oldSidecarLine = om.createObjectNode();
		oldSidecarLine.put("short_code", "bf_a");
		oldSidecarLine.put("recent_reels_avg_views", "9000");
		oldSidecarLine.put("rank_in_recent_reels", "1");
		oldSidecarLine.put("recent_reels_count", "2");
		oldSidecarLine.put("recent_contents_count", "3");
		oldSidecarLine.put("recent12_avg_engagement_rate", "0.0496");
		oldSidecarLine.put("recent12_avg_like_count", "940");
		oldSidecarLine.put("recent12_avg_comment_count", "61");
		oldSidecarLine.put("category_top_percentile", "67");
		oldSidecarLine.put("category_avg_views", "19333");
		oldSidecarLine.put("category_sample_size", "3");
		oldSidecarLine.put("caption", "캡션A");
		Files.createDirectories(workDir);
		Files.writeString(workDir.resolve("backfill-sidecar.jsonl"), om.writeValueAsString(oldSidecarLine) + "\n");
		resultJsonl = """
				{"key":"bf_a","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(INSIGHT_JSON));

		int saved = runner().collect("batches/b1");

		assertEquals(1, saved);
		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'bf_a'", String.class));
	}

	@Test
	void collect는_미완료_배치면_저장하지_않는다() {
		GeminiBackfillRunner pending = new GeminiBackfillRunner(db, ds, new GeminiBatchApi() {
			@Override
			public String uploadFile(byte[] jsonl, String displayName) {
				return "files/f1";
			}

			@Override
			public String createBatch(String model, String inputFileName, String displayName) {
				return "batches/b1";
			}

			@Override
			public String getBatch(String batchName) {
				return "{\"metadata\":{\"state\":\"JOB_STATE_RUNNING\"}}";
			}

			@Override
			public String downloadFile(String fileName) {
				throw new IllegalStateException("호출되면 안 됨");
			}
		}, new AnalyticsSettings(db), new BeautyTaxonomyLoader(ds), workDir);

		assertEquals(-1, pending.collect("batches/b1"));
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void Vertex_형식_결과라인은_에코_첫줄에서_short_code를_복원한다() {
		runner().submit();
		// Vertex 출력엔 key가 없다 — request 에코 첫 줄(GeminiContentAnalyzer.userText 포맷)에서 복원
		String echoText = """
				콘텐츠: bf_a (@acct1, reels)
				캡션: 캡션A
				지표: views=9000 likes=500 comments=50
				계정 기준선: {}
				댓글 분류 분포: {}

				위 콘텐츠를 분석하라.""";
		// downloadFile 결과는 줄 단위(JSONL)라 한 결과 라인은 물리적으로 한 줄이어야 한다 —
		// 텍스트 블록 대신 ObjectMapper로 조립해 개행이 섞이지 않게 한다.
		tools.jackson.databind.node.ObjectNode line = om.createObjectNode();
		line.put("status", "");
		tools.jackson.databind.node.ObjectNode request = line.putObject("request");
		request.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", echoText);
		line.putObject("response").putArray("candidates").addObject().putObject("content")
				.putArray("parts").addObject().put("text", INSIGHT_JSON);
		resultJsonl = om.writeValueAsString(line);

		int saved = runner().collect("batches/b1");

		assertEquals(1, saved);
		assertEquals("평균 수준", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'bf_a'", String.class));
	}

	@Test
	void Vertex_실패라인은_status가_있으면_실패로_센다() {
		runner().submit();
		// response는 정상 저장 가능한 유효 인사이트 — status만 비어있지 않으면 그 자체로 실패여야 한다.
		// (response를 빈 객체로 두면 이후 text.isMissingNode() 경로도 같은 결과를 내 status 분기가
		//  vacuous하게 통과한다 — 리뷰 지적 반영)
		tools.jackson.databind.node.ObjectNode line = om.createObjectNode();
		line.put("status", "INTERNAL");
		line.put("key", "bf_a");
		line.putObject("response").putArray("candidates").addObject().putObject("content")
				.putArray("parts").addObject().put("text", INSIGHT_JSON);
		resultJsonl = om.writeValueAsString(line);

		int saved = runner().collect("batches/b1");

		assertEquals(0, saved);
		// bf_done은 setUp에서 이미 적재된 기존 행 — status 분기가 없다면 이 라인은 정상 저장돼
		// count가 2로 늘었을 것 (bf_a는 사이드카에 있고 response도 유효하므로)
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void 잡_응답의_outputInfo_gcsOutputDirectory를_결과_위치로_쓴다() {
		runner().submit(); // 사이드카만 필요 — 결과 다운로드 fake는 아래서 별도 구성

		List<String> downloadedFiles = new java.util.ArrayList<>();
		GeminiBackfillRunner vertexRunner = new GeminiBackfillRunner(db, ds, new GeminiBatchApi() {
			@Override
			public String uploadFile(byte[] jsonl, String displayName) {
				return "files/f1";
			}

			@Override
			public String createBatch(String model, String inputFileName, String displayName) {
				return "batches/b1";
			}

			@Override
			public String getBatch(String batchName) {
				return """
						{"name":"batches/b1","state":"JOB_STATE_SUCCEEDED",
						 "outputInfo":{"gcsOutputDirectory":"gs://b/output/backfill/job-1"}}""";
			}

			@Override
			public String downloadFile(String fileName) {
				downloadedFiles.add(fileName);
				return "";
			}
		}, new AnalyticsSettings(db), new BeautyTaxonomyLoader(ds), workDir);

		vertexRunner.collect("batches/b1");

		assertEquals(List.of("gs://b/output/backfill/job-1"), downloadedFiles);
	}
}
