package com.celfit.was.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ArchiveWriterTest extends IntegrationTest {

	/**
	 * 자연인 식별 컬럼의 독립 기대값 — ArchiveTables.USER_PII를 그대로 참조하면 그 목록 자체에
	 * 오타가 나도 테스트가 자기 자신과 비교해 항상 통과한다(실측 확인됨). 두 상수가 서로를 잡아주도록
	 * 소스를 분리한다: 이 테스트는 EXPECTED_USER_PII로 payload를 검증하고, 아래에서 ArchiveTables.USER_PII와
	 * 내용이 일치하는지 별도로 확인한다.
	 */
	private static final List<String> EXPECTED_USER_PII = List.of(
			"email", "password_hash", "name", "nickname",
			"phone_country_code", "phone_number", "profile_image_url");

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

		int archived = archiveWriter.archiveByPk(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED,
				Map.of("user_id", userId, "short_code", "ABC123"));

		assertThat(archived).isEqualTo(1);

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

		archiveWriter.archiveUserScope(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED, userId);

		String memo = jdbcClient.sql("SELECT payload ->> 'memo' FROM archive.archived_rows WHERE user_id = :id")
				.param("id", userId)
				.query(String.class)
				.single();

		assertThat(memo).isEqualTo("메모다");
	}

	@Test
	void users는_직접_식별_컬럼을_제거하고_id는_남긴다() {
		long userId = insertUser("writer-3@example.com");

		archiveWriter.archiveUserScope(ArchiveTables.USERS, ArchiveReason.ACCOUNT_DELETION, userId);

		List<String> payloadKeys = jdbcClient.sql("""
						SELECT jsonb_object_keys(payload) FROM archive.archived_rows
						 WHERE table_name = 'app.users' AND user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.list();

		// 가명화 — 자연인 식별 컬럼 7종이 payload 키에 하나도 없어야 한다.
		assertThat(payloadKeys).doesNotContainAnyElementsOf(EXPECTED_USER_PII);
		assertThat(payloadKeys).contains("id");   // 자식 행과 조인하려면 id는 남아야 한다
		// 카탈로그(ArchiveTables.USER_PII)가 이 기대값과 동기화돼 있는지도 확인 — 한쪽만 바뀌면 여기서 잡힌다.
		assertThat(ArchiveTables.USER_PII).containsExactlyInAnyOrderElementsOf(EXPECTED_USER_PII);

		String companyName = jdbcClient.sql("""
						SELECT payload ->> 'company_name' FROM archive.archived_rows
						 WHERE table_name = 'app.users' AND user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.single();
		assertThat(companyName).isEqualTo("하입나우");   // company_name은 자산이라 보존
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

		int archived = archiveWriter.archiveByPk(ArchiveTables.MONITORING_REGISTRATION_ENTRIES,
				ArchiveReason.ACCOUNT_DELETION, Map.of("registration_id", registrationId, "seq", 1));

		assertThat(archived).isEqualTo(1);

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
		int archived = archiveWriter.archiveUserScope(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED, -1L);

		assertThat(archived).isZero();

		Long count = jdbcClient.sql("SELECT count(*) FROM archive.archived_rows WHERE user_id = -1")
				.query(Long.class)
				.single();

		assertThat(count).isZero();
	}

	@Test
	void archiveUserScope는_이관된_행_수를_돌려준다() {
		long userId = insertUser("writer-5@example.com");
		jdbcClient.sql("INSERT INTO app.saved_contents (user_id, short_code) VALUES (:id, 'A1'), (:id, 'A2')")
				.param("id", userId)
				.update();

		int archived = archiveWriter.archiveUserScope(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED, userId);

		assertThat(archived).isEqualTo(2);
	}

	@Test
	void archiveByPk에_pkColumns와_다른_키를_주면_예외() {
		assertThatThrownBy(() -> archiveWriter.archiveByPk(ArchiveTables.SAVED_CONTENTS, ArchiveReason.SAVED_REMOVED,
				Map.of("user_id", 1L, "wrong_column", "ABC123")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void ArchiveTable을_빈_pkColumns로_만들면_예외() {
		assertThatThrownBy(() -> new ArchiveTable("app.whatever", List.of(), "t.user_id", List.of(), "t.user_id = :userId"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void verifyMatched는_이관_건수와_삭제_건수가_다르면_예외() {
		assertThatThrownBy(() -> archiveWriter.verifyMatched(ArchiveTables.SAVED_CONTENTS, 0, 1))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.saved_contents")
				.hasMessageContaining("0")
				.hasMessageContaining("1");
	}

	@Test
	void verifyMatched는_이관_건수와_삭제_건수가_같으면_통과한다() {
		archiveWriter.verifyMatched(ArchiveTables.SAVED_CONTENTS, 3, 3);
		archiveWriter.verifyMatched(ArchiveTables.SAVED_CONTENTS, 0, 0);   // 대상 자체가 없던 경우도 일치로 취급
	}
}
