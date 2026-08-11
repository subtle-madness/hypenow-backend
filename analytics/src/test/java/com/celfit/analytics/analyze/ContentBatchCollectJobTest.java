package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.testsupport.TestDb;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 배치 수거 잡 계약(2026-08-11 — Vertex 배치 전송 전환): content_batch_jobs의 pending 행을
 * 순회해 SUCCEEDED는 저장·status=collected 전이, FAILED류는 status=failed 전이(재시도 없음 —
 * 다음날 후보 diff가 자연 재대상), 아직 실행 중이면 no-op. 파싱·저장 계약은
 * GeminiBackfillRunnerTest와 동형(GeminiBatchLines 공유).
 */
@Testcontainers
class ContentBatchCollectJobTest {

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

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		// AnalyticsSettings.activeLlmModel()이 app_setting을 읽는다 — 미설정이면 기본값(gemini) 사용.
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");
	}

	ContentBatchCollectJob collectJob(GeminiBatchApi api) {
		return new ContentBatchCollectJob(ds, api, new BeautyTaxonomyLoader(ds), new AnalyticsSettings(db), workDir);
	}

	void insertPendingBatchJob(String batchName, boolean timely, int submittedCount) {
		db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, status)
				VALUES (?, ?, ?, 'pending')""", batchName, timely, submittedCount);
	}

	/** 사이드카 라인 — GeminiBatchLines.SIDECAR_KEYS와 같은 키로 기준선 스냅샷 + caption + timely. */
	void writeSidecar(String batchName, String shortCode, boolean timely) {
		ObjectNode line = om.createObjectNode();
		line.put("short_code", shortCode);
		line.put("recent_reels_avg_views", "9000");
		line.put("rank_in_recent_reels", "1");
		line.put("recent_reels_count", "2");
		line.put("recent_contents_count", "3");
		line.put("recent12_avg_engagement_rate", "0.0496");
		line.put("recent12_avg_like_count", "940");
		line.put("recent12_avg_comment_count", "61");
		line.put("category_top_percentile", "67");
		line.put("category_avg_views", "19333");
		line.put("category_sample_size", "3");
		line.put("caption", "캡션A");
		line.put("timely", String.valueOf(timely));
		BatchSidecarStore.write(workDir, batchName, om.writeValueAsString(line) + "\n");
	}

	GeminiBatchApi succeededApi(String resultFile, String resultJsonl) {
		return new GeminiBatchApi() {
			@Override
			public String uploadFile(byte[] jsonl, String displayName) {
				throw new IllegalStateException("수거 테스트에서는 호출되면 안 됨");
			}

			@Override
			public String createBatch(String model, String inputFileName, String displayName) {
				throw new IllegalStateException("수거 테스트에서는 호출되면 안 됨");
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

	GeminiBatchApi stateOnlyApi(String state) {
		return new GeminiBatchApi() {
			@Override
			public String uploadFile(byte[] jsonl, String displayName) {
				throw new IllegalStateException("수거 테스트에서는 호출되면 안 됨");
			}

			@Override
			public String createBatch(String model, String inputFileName, String displayName) {
				throw new IllegalStateException("수거 테스트에서는 호출되면 안 됨");
			}

			@Override
			public String getBatch(String batchName) {
				return "{\"metadata\":{\"state\":\"%s\"}}".formatted(state);
			}

			@Override
			public void downloadResults(String fileName, Consumer<String> onLine) {
				throw new IllegalStateException("호출되면 안 됨 — state=" + state);
			}
		};
	}

	@Test
	void SUCCEEDED_배치는_결과를_저장하고_status를_collected로_전이한다() {
		insertPendingBatchJob("batches/b1", true, 1);
		writeSidecar("batches/b1", "cc_a", true);
		String resultJsonl = """
				{"key":"cc_a","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(INSIGHT_JSON));

		JobResult result = collectJob(succeededApi("files/r1", resultJsonl)).run();

		assertEquals(1, result.processed());
		assertEquals(0, result.failed());
		assertEquals("평균 수준", db.queryForObject(
				"SELECT ai_content_summary FROM content_analyses WHERE short_code = 'cc_a'", String.class));
		assertEquals("cleansing", db.queryForObject(
				"SELECT main_category FROM content_analyses WHERE short_code = 'cc_a'", String.class));
		// 사이드카의 timely=true를 그대로 승계
		assertEquals("timely", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'cc_a'", String.class));

		Map<String, Object> row = db.queryForMap(
				"SELECT status, collected_at FROM content_batch_jobs WHERE batch_name = 'batches/b1'");
		assertEquals("collected", row.get("status"));
		assertNotNull(row.get("collected_at"));
	}

	@Test
	void 수거는_멱등이다_재실행해도_행이_중복되지_않는다() {
		insertPendingBatchJob("batches/b1", true, 1);
		writeSidecar("batches/b1", "cc_a", true);
		String resultJsonl = """
				{"key":"cc_a","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(INSIGHT_JSON));
		GeminiBatchApi api = succeededApi("files/r1", resultJsonl);

		collectJob(api).run();
		// 재실행 — status가 이미 collected라 pending 조회에 안 잡히므로 downloadResults가 다시 불려도 무해
		JobResult second = collectJob(api).run();

		assertEquals(0, second.processed()); // pending 행이 이미 없어 대상 자체가 없다
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}

	@Test
	void FAILED_배치는_status_failed로_전이하고_저장하지_않는다() {
		insertPendingBatchJob("batches/b2", true, 1);

		JobResult result = collectJob(stateOnlyApi("JOB_STATE_FAILED")).run();

		assertEquals(0, result.processed());
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
		Map<String, Object> row = db.queryForMap(
				"SELECT status, collected_at, note FROM content_batch_jobs WHERE batch_name = 'batches/b2'");
		assertEquals("failed", row.get("status"));
		assertNotNull(row.get("collected_at"));
		assertNotNull(row.get("note"));
	}

	@Test
	void 실행_중인_배치는_no_op이고_pending을_유지한다() {
		insertPendingBatchJob("batches/b3", true, 1);

		JobResult result = collectJob(stateOnlyApi("JOB_STATE_RUNNING")).run();

		assertEquals(0, result.processed());
		assertEquals(0, result.failed());
		assertEquals("pending", db.queryForObject(
				"SELECT status FROM content_batch_jobs WHERE batch_name = 'batches/b3'", String.class));
	}

	@Test
	void 배치_미지원_GeminiApi면_run은_no_op이다() {
		insertPendingBatchJob("batches/b4", true, 1);

		JobResult result = collectJob(null).run();

		assertEquals(0, result.processed());
		assertEquals(0, result.failed());
		assertEquals("pending", db.queryForObject(
				"SELECT status FROM content_batch_jobs WHERE batch_name = 'batches/b4'", String.class));
	}

	@Test
	void 대상이_없으면_아무_일도_하지_않는다() {
		JobResult result = collectJob(succeededApi("files/r1", "")).run();

		assertEquals(0, result.processed());
		assertEquals(0, result.failed());
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_batch_jobs", Long.class));
	}
}
