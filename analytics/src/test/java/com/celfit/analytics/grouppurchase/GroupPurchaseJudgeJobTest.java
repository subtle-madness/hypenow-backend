package com.celfit.analytics.grouppurchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.analyze.JobResult;
import com.celfit.analytics.analyze.ProgressReporter;
import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.testsupport.TestDb;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 공동구매 판정 잡 통합 계약: 규칙 확정분은 LLM 없이 기록, 애매분만 LLM 호출·해시 재판정,
 * LLM 실패는 verdict NULL로 재시도 대상, 연속 실패 임계 도달 시 잔여 후보 미터치, 킬 스위치.
 */
class GroupPurchaseJudgeJobTest {

	static final PostgreSQLContainer pg = TestDb.shared();

	JdbcTemplate db;
	DataSource ds;
	AnalyticsSettings settings;
	List<String> llmCalls;
	GroupPurchaseJudgeJob job;

	@BeforeEach
	void setUp() {
		ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		db.update("CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL)");
		settings = new AnalyticsSettings(db);
		llmCalls = new ArrayList<>();
		rewireJob(recordingTruePort());
	}

	void rewireJob(GroupPurchaseJudgePort port) {
		job = new GroupPurchaseJudgeJob(ds, port, settings, ProgressReporter.NOOP);
	}

	GroupPurchaseJudgePort recordingTruePort() {
		return caption -> {
			llmCalls.add(caption);
			return new GroupPurchaseJudgePort.Judgment(true, "LLM: " + caption);
		};
	}

	void insertContent(String shortCode, String caption) {
		db.update("""
				INSERT INTO contents (short_code, account_handle, caption, posted_at)
				VALUES (?, 'acct1', ?, now())""", shortCode, caption);
	}

	/**
	 * 후보 SQL이 캡션에 '공구'·'공동구매'가 없으면 애초에 뽑지 않으므로(스펙 §5 — 5천여 건 스캔
	 * 최적화), 규칙 표 5행(CONFIRMED_FALSE)은 신규 캡션에서는 도달하지 않는다 — 오직 "이전에
	 * 판정됐던 캡션이 수정으로 키워드를 잃는" 재판정 경로에서만 나온다(아래
	 * 캡션이_바뀌어_공구가_사라지면_거짓으로_재판정된다). 여기서는 CONFIRMED_TRUE(규칙 1·4행)만 검증.
	 */
	@Test
	void 규칙_확정_참은_LLM_없이_기록된다() {
		insertContent("rule_confirmed", "이번주 공동구매 시작합니다");
		insertContent("rule_bare", "공구 재입고 안내드립니다");

		JobResult result = job.run();

		assertEquals(2, result.processed());
		assertTrue(llmCalls.isEmpty());
		assertEquals("RULE", db.queryForObject(
				"SELECT tier FROM group_purchase_judgments WHERE short_code='rule_confirmed'", String.class));
		assertTrue(db.queryForObject(
				"SELECT verdict FROM group_purchase_judgments WHERE short_code='rule_confirmed'", Boolean.class));
		assertTrue(db.queryForObject(
				"SELECT verdict FROM group_purchase_judgments WHERE short_code='rule_bare'", Boolean.class));
		assertNull(db.queryForObject(
				"SELECT model FROM group_purchase_judgments WHERE short_code='rule_confirmed'", String.class));
	}

	@Test
	void 캡션이_바뀌어_공구가_사라지면_거짓으로_재판정된다() {
		insertContent("was_true", "공동구매 오픈합니다");
		job.run();
		assertTrue(db.queryForObject(
				"SELECT verdict FROM group_purchase_judgments WHERE short_code='was_true'", Boolean.class));

		db.update("UPDATE contents SET caption = '오늘의 데일리룩 소개' WHERE short_code='was_true'");
		JobResult result = job.run();

		assertEquals(1, result.processed());
		assertFalse(db.queryForObject(
				"SELECT verdict FROM group_purchase_judgments WHERE short_code='was_true'", Boolean.class));
		assertEquals("RULE", db.queryForObject(
				"SELECT tier FROM group_purchase_judgments WHERE short_code='was_true'", String.class));
	}

	@Test
	void 애매분은_LLM을_호출하고_tier_LLM으로_저장한다() {
		insertContent("amb1", "공구 없이 조립했어요");

		JobResult result = job.run();

		assertEquals(1, result.processed());
		assertEquals(List.of("공구 없이 조립했어요"), llmCalls);
		Map<String, Object> row = db.queryForMap(
				"SELECT tier, verdict, model FROM group_purchase_judgments WHERE short_code='amb1'");
		assertEquals("LLM", row.get("tier"));
		assertEquals(true, row.get("verdict"));
		assertEquals(settings.geminiModel(), row.get("model"));
	}

	@Test
	void 캡션_불변이면_재실행은_아무것도_하지_않는다() {
		insertContent("amb1", "공구 없이 조립했어요");
		job.run();
		llmCalls.clear();

		JobResult result = job.run();

		assertEquals(0, result.processed());
		assertTrue(llmCalls.isEmpty());
	}

	@Test
	void 캡션이_바뀌면_해시가_달라져_재판정된다() {
		insertContent("amb1", "공구 없이 조립했어요");
		job.run();
		llmCalls.clear();

		db.update("UPDATE contents SET caption = '공구 없이 설치까지 다 했어요' WHERE short_code='amb1'");
		JobResult result = job.run();

		assertEquals(1, result.processed());
		assertEquals(1, llmCalls.size());
	}

	@Test
	void LLM_실패는_verdict_NULL로_남고_다음_실행이_재시도한다() {
		insertContent("amb1", "공구 없이 조립했어요");
		rewireJob(caption -> { throw new RuntimeException("timeout"); });

		JobResult first = job.run();

		assertEquals(0, first.processed());
		assertEquals(1, first.failed());
		assertNull(db.queryForObject(
				"SELECT verdict FROM group_purchase_judgments WHERE short_code='amb1'", Boolean.class));
		assertEquals("LLM", db.queryForObject(
				"SELECT tier FROM group_purchase_judgments WHERE short_code='amb1'", String.class));

		rewireJob(recordingTruePort());
		JobResult second = job.run();

		assertEquals(1, second.processed());
		assertEquals(1, llmCalls.size());
		assertTrue(db.queryForObject(
				"SELECT verdict FROM group_purchase_judgments WHERE short_code='amb1'", Boolean.class));
	}

	@Test
	void 킬_스위치가_꺼지면_아무_행도_쓰지_않는다() {
		db.update("INSERT INTO app_setting (key, value) VALUES (?, 'false')",
				AnalyticsSettings.KEY_GROUP_PURCHASE_ENABLED);
		insertContent("rule_true", "공동구매 시작");

		JobResult result = job.run();

		assertEquals(0, result.processed());
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM group_purchase_judgments", Long.class));
	}

	@Test
	void 연속_실패_임계에_도달하면_잔여_후보는_손대지_않고_중단한다() {
		for (int i = 0; i < GroupPurchaseJudgeJob.LLM_FAILURE_ABORT_THRESHOLD + 2; i++) {
			insertContent("amb" + i, "공구 없이 조립 " + i);
		}
		AtomicInteger calls = new AtomicInteger();
		rewireJob(caption -> {
			calls.incrementAndGet();
			throw new RuntimeException("boom");
		});

		JobResult result = job.run();

		assertEquals(GroupPurchaseJudgeJob.LLM_FAILURE_ABORT_THRESHOLD, calls.get());
		assertEquals(GroupPurchaseJudgeJob.LLM_FAILURE_ABORT_THRESHOLD, result.failed());
		assertTrue(result.carriedOver());
		assertEquals((long) GroupPurchaseJudgeJob.LLM_FAILURE_ABORT_THRESHOLD, (long) db.queryForObject(
				"SELECT count(*) FROM group_purchase_judgments", Long.class));
	}
}
