package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.celfit.analytics.testsupport.TestDb;
import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.AccountAnalysis;
import com.celfit.contract.analysis.AccountCategoryStat;
import com.celfit.contract.analysis.AccountContentPoint;
import com.celfit.contract.analysis.AccountSummary;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import com.celfit.contract.analysis.ContentMetricSnapshot;
import com.celfit.contract.analysis.CrawlCallDaily;
import com.celfit.contract.analysis.LandingStats;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Flyway DDL ↔ contract record 대조: 세 아티팩트(뷰/DDL/record) 중 "DDL=record" 경계를
 * 테스트 타임에 고정한다 ("뷰=record"는 MirrorJob 런타임 가드 담당).
 */
class FlywaySchemaTest {

	static final PostgreSQLContainer pg = TestDb.shared();

	static JdbcTemplate db;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		// 공유 컨테이너라 이전 클래스의 잔재 위에서 migrate하면 안 된다 — 리셋 후 전체 재생
		TestDb.resetAndMigrate(db, ds);
	}

	@Test
	void accounts_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("accounts", Account.class);
	}

	@Test
	void contents_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("contents", Content.class);
	}

	@Test
	void content_comments_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("content_comments", ContentComment.class);
	}

	@Test
	void content_metric_snapshots_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("content_metric_snapshots", ContentMetricSnapshot.class);
	}

	@Test
	void account_summaries_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("account_summaries", AccountSummary.class);
	}

	/** account_category_stats는 미러 테이블이 아니라 analysis DB 파생 뷰(V35)지만 계약은 동일하다. */
	@Test
	void account_category_stats_뷰_컬럼이_record와_일치한다() {
		assertColumnsMatch("account_category_stats", AccountCategoryStat.class);
	}

	@Test
	void account_content_series_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("account_content_series", AccountContentPoint.class);
	}

	@Test
	void account_analyses_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("account_analyses", AccountAnalysis.class);
	}

	@Test
	void landing_stats_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("landing_stats", LandingStats.class);
	}

	@Test
	void crawl_call_daily_테이블_컬럼이_record와_일치한다() {
		assertColumnsMatch("crawl_call_daily", CrawlCallDaily.class);
	}

	private void assertColumnsMatch(String table, Class<? extends Record> recordType) {
		List<String> tableColumns = db.queryForList("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = ?
				ORDER BY ordinal_position""", String.class, table);
		List<String> recordColumns = Arrays.stream(recordType.getRecordComponents())
				.map(RecordComponent::getName)
				.map(MirrorJob::toSnakeCase)
				.toList();
		assertEquals(recordColumns, tableColumns, table + " 컬럼이 record와 다름");
	}
}
