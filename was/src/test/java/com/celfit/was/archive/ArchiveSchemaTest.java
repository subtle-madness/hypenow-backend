package com.celfit.was.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** archive 스키마가 Flyway로 실제 생성되는지 검증 — DDL 하드코딩 없음. */
class ArchiveSchemaTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void archived_rows_테이블이_archive_스키마에_생성된다() {
		List<String> columns = jdbcClient.sql("""
						SELECT column_name FROM information_schema.columns
						 WHERE table_schema = 'archive' AND table_name = 'archived_rows'
						 ORDER BY ordinal_position
						""")
				.query(String.class)
				.list();

		assertThat(columns).containsExactly(
				"id", "table_name", "row_pk", "user_id", "payload", "archived_at", "archived_reason");
	}

	@Test
	void payload와_row_pk는_jsonb다() {
		List<String> types = jdbcClient.sql("""
						SELECT data_type FROM information_schema.columns
						 WHERE table_schema = 'archive' AND table_name = 'archived_rows'
						   AND column_name IN ('row_pk', 'payload')
						""")
				.query(String.class)
				.list();

		assertThat(types).containsExactly("jsonb", "jsonb");
	}
}
