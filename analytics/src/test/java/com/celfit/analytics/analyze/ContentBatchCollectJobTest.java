package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.testsupport.TestDb;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * 배치 수거 잡 계약(2026-08-11 — Vertex 배치 전송 전환): content_batch_jobs의 pending 행을
 * 순회해 SUCCEEDED는 저장·status=collected 전이, FAILED류는 status=failed 전이(재시도 없음 —
 * 다음날 후보 diff가 자연 재대상), 아직 실행 중이면 no-op. 파싱·저장 계약은
 * GeminiBackfillRunnerTest와 동형(GeminiBatchLines 공유).
 *
 * <p>사이드카는 content_batch_jobs.sidecar_jsonl 컬럼에 저장한다(로컬 파일 아님 — analytics
 * 컨테이너에는 쓰기 가능한 볼륨이 없어 제출~수거 사이 배포가 끼면 파일이 유실돼 pending 좀비가
 * 남는다, 2026-08-11 리뷰 반영).
 */
@Testcontainers
class ContentBatchCollectJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static final String INSIGHT_JSON = """
			{"detectedBrands":null,"sponsoredSignalLevel":"low","sponsoredSignalReasons":null,
			 "adDisclosure":"표기 없음","detectedProductCategories":["클렌징폼"],"detectedProducts":null,
			 "vlmAttributes":null,"mainCategory":"cleansing","subCategories":["클렌징폼"],
			 "detectedDistributors":null,"adType":"organic","isRelevant":true,
			 "aiContentSummary":"평균 수준","contentsPattern":"루틴형","aiCommentInsight":"표본 부족",
			 "commentAuthenticityGrade":"normal","commentAuthenticityNote":"근거"}"""
			.replace("\n", "");

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
		return new ContentBatchCollectJob(ds, api, new BeautyTaxonomyLoader(ds), new AnalyticsSettings(db));
	}

	void insertPendingBatchJob(String batchName, boolean timely, int submittedCount) {
		insertPendingBatchJob(batchName, timely, submittedCount, null);
	}

	void insertPendingBatchJob(String batchName, boolean timely, int submittedCount, String sidecarJsonl) {
		db.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, status, sidecar_jsonl)
				VALUES (?, ?, ?, 'pending', ?)""", batchName, timely, submittedCount, sidecarJsonl);
	}

	/** 사이드카 라인(JSONL 1줄) — GeminiBatchLines.SIDECAR_KEYS와 같은 키로 기준선 스냅샷 + caption + timely. */
	String sidecarLine(String shortCode, boolean timely) {
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
		return om.writeValueAsString(line) + "\n";
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
	void SUCCEEDED_배치는_결과를_저장하고_status를_collected로_전이하고_사이드카를_비운다() {
		insertPendingBatchJob("batches/b1", true, 1, sidecarLine("cc_a", true));
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
				"SELECT status, collected_at, sidecar_jsonl FROM content_batch_jobs WHERE batch_name = 'batches/b1'");
		assertEquals("collected", row.get("status"));
		assertNotNull(row.get("collected_at"));
		// 수거 완료 후 sidecar_jsonl은 NULL로 비워 테이블 비대를 막는다(2026-08-11 리뷰 반영)
		assertNull(row.get("sidecar_jsonl"));
	}

	@Test
	void 수거는_멱등이다_재실행해도_행이_중복되지_않는다() {
		insertPendingBatchJob("batches/b1", true, 1, sidecarLine("cc_a", true));
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
		insertPendingBatchJob("batches/b2", true, 1, sidecarLine("cc_b", true));

		JobResult result = collectJob(stateOnlyApi("JOB_STATE_FAILED")).run();

		assertEquals(0, result.processed());
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
		Map<String, Object> row = db.queryForMap(
				"SELECT status, collected_at, note, sidecar_jsonl FROM content_batch_jobs WHERE batch_name = 'batches/b2'");
		assertEquals("failed", row.get("status"));
		assertNotNull(row.get("collected_at"));
		assertNotNull(row.get("note"));
		assertNull(row.get("sidecar_jsonl")); // failed 전이도 사이드카를 비운다
	}

	@Test
	void 실행_중인_배치는_no_op이고_pending을_유지한다() {
		insertPendingBatchJob("batches/b3", true, 1, sidecarLine("cc_c", true));

		JobResult result = collectJob(stateOnlyApi("JOB_STATE_RUNNING")).run();

		assertEquals(0, result.processed());
		assertEquals(0, result.failed());
		assertEquals("pending", db.queryForObject(
				"SELECT status FROM content_batch_jobs WHERE batch_name = 'batches/b3'", String.class));
	}

	@Test
	void 배치_미지원_GeminiApi면_run은_no_op이다() {
		insertPendingBatchJob("batches/b4", true, 1, sidecarLine("cc_d", true));

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

	@Test
	void 사이드카_유실_pending은_failed로_접히고_좀비로_남지_않는다() {
		// 배포·컨테이너 교체가 제출~수거 사이에 끼는 시나리오 재현 대신, sidecar_jsonl이 NULL인
		// pending 행을 직접 시딩한다(2026-08-11 리뷰 지적 — 파일 방식이던 시절엔 이 케이스가
		// IllegalStateException으로 catch는 되지만 status는 그대로 pending에 남는 좀비였다).
		insertPendingBatchJob("batches/b5", true, 1); // sidecar_jsonl=NULL
		String resultJsonl = """
				{"key":"cc_e","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(INSIGHT_JSON));

		JobResult result = collectJob(succeededApi("files/r1", resultJsonl)).run();

		assertEquals(0, result.processed());
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
		Map<String, Object> row = db.queryForMap(
				"SELECT status, collected_at, note FROM content_batch_jobs WHERE batch_name = 'batches/b5'");
		assertEquals("failed", row.get("status")); // pending에 영원히 남지 않는다
		assertNotNull(row.get("collected_at"));
		assertNotNull(row.get("note"));
	}

	@Test
	void 여러_pending_배치_중_사이드카_유실분만_failed로_접히고_나머지는_정상_수거된다() {
		// 한 실행 안에 정상 배치와 사이드카 유실 배치가 섞여도 잡 전체가 죽지 않고 각자 격리 처리된다.
		insertPendingBatchJob("batches/ok", true, 1, sidecarLine("cc_ok", true));
		insertPendingBatchJob("batches/lost", true, 1); // sidecar_jsonl=NULL
		String okResult = """
				{"key":"cc_ok","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(INSIGHT_JSON));
		String lostResult = """
				{"key":"cc_lost","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(INSIGHT_JSON));
		GeminiBatchApi api = new GeminiBatchApi() {
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
				String resultFile = "batches/ok".equals(batchName) ? "files/ok" : "files/lost";
				return """
						{"name":"%s","metadata":{"state":"JOB_STATE_SUCCEEDED",
						 "output":{"responsesFile":"%s"}}}""".formatted(batchName, resultFile);
			}

			@Override
			public void downloadResults(String fileName, Consumer<String> onLine) {
				String body = "files/ok".equals(fileName) ? okResult : lostResult;
				body.lines().filter(l -> !l.isBlank()).forEach(onLine);
			}
		};

		JobResult result = collectJob(api).run();

		assertEquals(1, result.processed()); // batches/ok만 저장
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
		assertEquals("collected", db.queryForObject(
				"SELECT status FROM content_batch_jobs WHERE batch_name = 'batches/ok'", String.class));
		assertEquals("failed", db.queryForObject(
				"SELECT status FROM content_batch_jobs WHERE batch_name = 'batches/lost'", String.class));
	}
}
