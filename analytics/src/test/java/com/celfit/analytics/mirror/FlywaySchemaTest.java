package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Flyway DDL ↔ contract record 대조: 세 아티팩트(뷰/DDL/record) 중 "DDL=record" 경계를
 * 테스트 타임에 고정한다 ("뷰=record"는 MirrorJob 런타임 가드 담당).
 */
@Testcontainers
class FlywaySchemaTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static JdbcTemplate db;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		Flyway.configure().dataSource(ds).locations("classpath:db/migration/analysis").load().migrate();
		db = new JdbcTemplate(ds);
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
