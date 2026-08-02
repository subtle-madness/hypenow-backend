package com.celfit.was.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ArchiveWriterTest extends IntegrationTest {

	@Autowired
	ArchiveWriter archiveWriter;

	@Autowired
	JdbcClient jdbcClient;

	private long insertUser(String email) {
		return jdbcClient.sql("""
						INSERT INTO app.users (email, password_hash, name, nickname, phone_number, company_name)
						VALUES (:email, 'hash', '홍길동', '길동', '01012345678', '하입나우')
						RETURNING id
						""")
				.param("email", email)
				.query(Long.class)
				.single();
	}

	@Test
	void 복합PK_테이블은_row_pk에_두_컬럼을_모두_담는다() {
		long userId = insertUser("writer-1@example.com");
		jdbcClient.sql("INSERT INTO app.saved_contents (user_id, short_code) VALUES (:id, 'ABC123')")
				.param("id", userId)
				.update();

		archiveWriter.archive(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED,
				"t.user_id = :userId AND t.short_code = :shortCode",
				Map.of("userId", userId, "shortCode", "ABC123"));

		Map<String, Object> row = jdbcClient.sql("""
						SELECT table_name, row_pk::text AS row_pk, user_id, archived_reason
						  FROM archive.archived_rows WHERE user_id = :id
						""")
				.param("id", userId)
				.query()
				.singleRow();

		assertThat(row.get("table_name")).isEqualTo("app.saved_contents");
		assertThat(row.get("row_pk").toString()).contains("\"user_id\": " + userId).contains("\"short_code\": \"ABC123\"");
		assertThat(row.get("user_id")).isEqualTo(userId);
		assertThat(row.get("archived_reason")).isEqualTo("SAVED_REMOVED");
	}

	@Test
	void payload는_원본_행_전체를_담는다() {
		long userId = insertUser("writer-2@example.com");
		jdbcClient.sql("INSERT INTO app.saved_contents (user_id, short_code, memo) VALUES (:id, 'XYZ789', '메모다')")
				.param("id", userId)
				.update();

		archiveWriter.archive(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED,
				"t.user_id = :userId", Map.of("userId", userId));

		String memo = jdbcClient.sql("SELECT payload ->> 'memo' FROM archive.archived_rows WHERE user_id = :id")
				.param("id", userId)
				.query(String.class)
				.single();

		assertThat(memo).isEqualTo("메모다");
	}

	@Test
	void users는_직접_식별_컬럼을_제거하고_id는_남긴다() {
		long userId = insertUser("writer-3@example.com");

		archiveWriter.archive(ArchiveTables.USERS, ArchiveReason.ACCOUNT_DELETION,
				"t.id = :userId", Map.of("userId", userId));

		Map<String, Object> row = jdbcClient.sql("""
						SELECT payload::text AS payload, user_id FROM archive.archived_rows
						 WHERE table_name = 'app.users' AND user_id = :id
						""")
				.param("id", userId)
				.query()
				.singleRow();

		String payload = row.get("payload").toString();
		assertThat(payload)
				.doesNotContain("writer-3@example.com")
				.doesNotContain("password_hash")
				.doesNotContain("홍길동")
				.doesNotContain("01012345678");
		assertThat(payload).contains("하입나우");   // company_name은 자산이라 보존
		assertThat(row.get("user_id")).isEqualTo(userId);
	}

	@Test
	void user_id_컬럼이_없는_테이블은_user_id를_NULL로_남긴다() {
		long userId = insertUser("writer-4@example.com");
		long registrationId = jdbcClient.sql("""
						INSERT INTO app.monitoring_registrations (user_id)
						VALUES (:id) RETURNING id
						""")
				.param("id", userId)
				.query(Long.class)
				.single();
		jdbcClient.sql("""
						INSERT INTO app.monitoring_registration_entries (registration_id, seq, input, kind, result)
						VALUES (:rid, 1, 'someaccount', 'account', 'pending')
						""")
				.param("rid", registrationId)
				.update();

		archiveWriter.archive(ArchiveTables.MONITORING_REGISTRATION_ENTRIES, ArchiveReason.ACCOUNT_DELETION,
				"t.registration_id = :registrationId", Map.of("registrationId", registrationId));

		Map<String, Object> row = jdbcClient.sql("""
						SELECT user_id, row_pk::text AS row_pk FROM archive.archived_rows
						 WHERE table_name = 'app.monitoring_registration_entries'
						   AND row_pk ->> 'registration_id' = :registrationId
						""")
				.param("registrationId", String.valueOf(registrationId))
				.query()
				.singleRow();

		assertThat(row.get("user_id")).isNull();
		assertThat(row.get("row_pk").toString()).contains("\"registration_id\": " + registrationId).contains("\"seq\": 1");
	}

	@Test
	void 대상_행이_없으면_아무것도_안_남기고_예외도_없다() {
		archiveWriter.archive(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED,
				"t.user_id = :userId", Map.of("userId", -1L));

		Long count = jdbcClient.sql("SELECT count(*) FROM archive.archived_rows WHERE user_id = -1")
				.query(Long.class)
				.single();

		assertThat(count).isZero();
	}
}
