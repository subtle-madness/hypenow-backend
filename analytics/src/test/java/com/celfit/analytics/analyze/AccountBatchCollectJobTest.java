package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AdSituation;
import com.celfit.analytics.llm.CopyRules;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.llm.TraitTaxonomyLoader;
import com.celfit.analytics.testsupport.TestDb;
import java.time.OffsetDateTime;
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
 * 계정 카피 배치 수거 잡 계약(2026-08-17): account_batch_jobs의 pending 행을 순회해 SUCCEEDED는
 * 저장·status=collected 전이, FAILED류는 status=failed 전이(재시도 없음), 아직 실행 중이면 no-op.
 * ContentBatchCollectJobTest와 동형(콘텐츠 쪽 셋업·fake GeminiBatchApi 관용을 그대로 미러) — 차이는
 * 대상 테이블·사이드카 필드(계정은 timely가 없다)뿐이다.
 */
@Testcontainers
class AccountBatchCollectJobTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

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

	AccountBatchCollectJob collectJob(GeminiBatchApi api) {
		return new AccountBatchCollectJob(ds, api, new TraitTaxonomyLoader(ds), new AnalyticsSettings(db));
	}

	void insertPendingBatchJob(String batchName, int submittedCount) {
		insertPendingBatchJob(batchName, submittedCount, null);
	}

	void insertPendingBatchJob(String batchName, int submittedCount, String sidecarJsonl) {
		db.update("""
				INSERT INTO account_batch_jobs (batch_name, submitted_count, status, sidecar_jsonl)
				VALUES (?, ?, 'pending', ?)""", batchName, submittedCount, sidecarJsonl);
	}

	/** 사이드카 라인(JSONL 1줄) — AccountBatchLines.sidecarLine을 그대로 직렬화(같은 패키지). */
	String sidecarLine(String handle, OffsetDateTime lastPostedAt, Long analyzedCount, AdSituation adSituation) {
		return om.writeValueAsString(AccountBatchLines.sidecarLine(om, handle, lastPostedAt, analyzedCount, adSituation)) + "\n";
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
		OffsetDateTime lastPostedAt = OffsetDateTime.parse("2026-07-01T09:00:00+09:00");
		insertPendingBatchJob("batches/a1", 1,
				sidecarLine("beauty_kim", lastPostedAt, 34L, AdSituation.COMPARABLE));
		String copyJson = """
				{"tagline":"저자극 스킨케어 리뷰","traits":["성분 분석"],"perfSummary":"성과 요약",
				 "contentSummary":"콘텐츠 요약","adSummary":"광고 요약"}""";
		String resultJsonl = """
				{"key":"beauty_kim","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(copyJson));

		JobResult result = collectJob(succeededApi("files/r1", resultJsonl)).run();

		assertEquals(1, result.processed());
		assertEquals(0, result.failed());
		Map<String, Object> analysis = db.queryForMap(
				"SELECT input_analyzed_count, copy_version, tagline FROM account_analyses WHERE handle = 'beauty_kim'");
		assertEquals(34L, ((Number) analysis.get("input_analyzed_count")).longValue());
		assertEquals(1L, db.queryForObject("""
				SELECT count(*) FROM account_analyses
				WHERE handle = 'beauty_kim' AND input_last_posted_at = ?""", Long.class, lastPostedAt));
		assertEquals(CopyRules.VERSION, ((Number) analysis.get("copy_version")).intValue());
		assertEquals("저자극 스킨케어 리뷰", analysis.get("tagline"));

		Map<String, Object> row = db.queryForMap(
				"SELECT status, collected_at, sidecar_jsonl FROM account_batch_jobs WHERE batch_name = 'batches/a1'");
		assertEquals("collected", row.get("status"));
		assertNotNull(row.get("collected_at"));
		assertNull(row.get("sidecar_jsonl"));
	}

	@Test
	void 실행_중_배치는_pending을_유지하고_다운로드를_호출하지_않는다() {
		insertPendingBatchJob("batches/a2", 1,
				sidecarLine("beauty_running", OffsetDateTime.now(), 10L, AdSituation.COMPARABLE));

		JobResult result = collectJob(stateOnlyApi("JOB_STATE_RUNNING")).run();

		assertEquals(0, result.processed());
		assertEquals(0, result.failed());
		assertEquals("pending", db.queryForObject(
				"SELECT status FROM account_batch_jobs WHERE batch_name = 'batches/a2'", String.class));
	}

	@Test
	void 실패_상태_배치는_failed로_전이하고_note에_상태를_남긴다() {
		insertPendingBatchJob("batches/a3", 1,
				sidecarLine("beauty_failed", OffsetDateTime.now(), 10L, AdSituation.COMPARABLE));

		JobResult result = collectJob(stateOnlyApi("JOB_STATE_FAILED")).run();

		assertEquals(0, result.processed());
		Map<String, Object> row = db.queryForMap(
				"SELECT status, note, sidecar_jsonl FROM account_batch_jobs WHERE batch_name = 'batches/a3'");
		assertEquals("failed", row.get("status"));
		assertTrue(((String) row.get("note")).contains("JOB_STATE_FAILED"), (String) row.get("note"));
		assertNull(row.get("sidecar_jsonl"));
	}

	@Test
	void 사이드카_유실_pending은_failed로_접히고_좀비로_남지_않는다() {
		insertPendingBatchJob("batches/a4", 1); // sidecar_jsonl=NULL
		String copyJson = """
				{"tagline":"태그","traits":["성분 분석"],"perfSummary":"성과 요약",
				 "contentSummary":"콘텐츠 요약","adSummary":"광고 요약"}""";
		String resultJsonl = """
				{"key":"beauty_lost","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(copyJson));

		JobResult result = collectJob(succeededApi("files/r1", resultJsonl)).run();

		assertEquals(0, result.processed());
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
		assertEquals("failed", db.queryForObject(
				"SELECT status FROM account_batch_jobs WHERE batch_name = 'batches/a4'", String.class));
	}

	/** 콘텐츠의 "여러_pending_배치_중_사이드카_유실분만_failed로_접히고_나머지는_정상_수거된다" 미러 —
	 *  한 실행 안에 정상 배치와 사이드카 유실 배치가 섞여도 run() 루프의 행 단위 try-catch가 각자
	 *  격리 처리한다(리뷰 지적 — 이 격리를 깨는 회귀를 잡기 위한 테스트). */
	@Test
	void 여러_pending_배치_중_사이드카_유실분만_failed로_접히고_나머지는_정상_수거된다() {
		insertPendingBatchJob("batches/ok", 1,
				sidecarLine("beauty_ok", OffsetDateTime.parse("2026-07-01T09:00:00+09:00"), 20L, AdSituation.COMPARABLE));
		insertPendingBatchJob("batches/lost", 1); // sidecar_jsonl=NULL
		String copyJson = """
				{"tagline":"태그","traits":["성분 분석"],"perfSummary":"성과 요약",
				 "contentSummary":"콘텐츠 요약","adSummary":"광고 요약"}""";
		String okResult = """
				{"key":"beauty_ok","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(copyJson));
		String lostResult = """
				{"key":"beauty_lost","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(copyJson));
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
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM account_analyses WHERE handle = 'beauty_ok'", Long.class));
		assertEquals("collected", db.queryForObject(
				"SELECT status FROM account_batch_jobs WHERE batch_name = 'batches/ok'", String.class));
		assertEquals("failed", db.queryForObject(
				"SELECT status FROM account_batch_jobs WHERE batch_name = 'batches/lost'", String.class));
	}

	/** 콘텐츠의 "배치_미지원_GeminiApi면_run은_no_op이다" 미러 — batchApi=null이면 pending 조회조차
	 *  하지 않고 조용히 no-op이어야 한다(배치 미지원 프로바이더 방어). */
	@Test
	void 배치_미지원_GeminiApi면_run은_no_op이다() {
		insertPendingBatchJob("batches/a5", 1,
				sidecarLine("beauty_noop", OffsetDateTime.now(), 10L, AdSituation.COMPARABLE));

		JobResult result = collectJob(null).run();

		assertEquals(0, result.processed());
		assertEquals(0, result.failed());
		assertEquals("pending", db.queryForObject(
				"SELECT status FROM account_batch_jobs WHERE batch_name = 'batches/a5'", String.class));
	}

	/** AccountBatchLines.processResultLine 안 AdSituation.valueOf가 어휘에 없는 문자열(사이드카
	 *  오염·구버전 스냅샷 등)을 만나면 IllegalArgumentException을 던진다 — 이게 포괄 catch(Exception)에
	 *  잡혀 해당 라인만 격리(false 처리)되고 배치 전체는 정상 collected로 전이돼야 한다(리뷰 지적 —
	 *  이 방어를 깨는 회귀를 잡기 위한 테스트). */
	@Test
	void ad_situation_불량값_라인은_저장을_생략하고_배치는_collected로_전이된다() {
		String badSidecar = """
				{"handle":"beauty_bogus","last_posted_at":"2026-07-01T09:00:00+09:00",\
				"analyzed_count":"10","ad_situation":"BOGUS"}
				""";
		insertPendingBatchJob("batches/a6", 1, badSidecar);
		String copyJson = """
				{"tagline":"태그","traits":["성분 분석"],"perfSummary":"성과 요약",
				 "contentSummary":"콘텐츠 요약","adSummary":"광고 요약"}""";
		String resultJsonl = """
				{"key":"beauty_bogus","response":{"candidates":[{"content":{"parts":[{"text":%s}]}}]}}"""
				.formatted(om.writeValueAsString(copyJson));

		JobResult result = collectJob(succeededApi("files/r1", resultJsonl)).run();

		assertEquals(0, result.processed()); // 불량 라인은 저장 실패 처리
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM account_analyses", Long.class));
		assertEquals("collected", db.queryForObject(
				"SELECT status FROM account_batch_jobs WHERE batch_name = 'batches/a6'", String.class));
	}
}
