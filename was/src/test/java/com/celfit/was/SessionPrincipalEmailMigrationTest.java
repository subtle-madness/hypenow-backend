package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 구 email-principal 세션 이전 마이그레이션(V20260903080645__session_principal_email_to_user_id.sql,
 * 트랙 A 09-03 리뷰 수정 — Important 1) 검증. Flyway는 컨테이너 기동 시 이미 한 번 돌아 이 파일도
 * 적용된 상태라 재실행할 수 없으므로, SQL 문자열을 클래스패스 리소스로 그대로 읽어와 직접 실행한다
 * (마이그레이션 SQL 정본이 파일 하나뿐이도록 — 테스트에 SQL을 중복 작성하지 않는다).
 *
 * <p>검증 대상은 (a) principal_name이 이메일인 구 세션이 해당 유저의 userId 문자열로 이전되는지,
 * (b) principal_name이 이미 userId 문자열인 신규 세션·다른 유저의 이메일과도 안 겹치는 무관한 값은
 * 건드리지 않는지, (c) 마이그레이션 SQL 자체가 Flyway 재생 경로에서 문법 오류 없이 적용됐다는 사실
 * (컨테이너 기동이 성공했다는 것 자체가 그 증거 — IntegrationTest 상속이 이를 암묵적으로 확인한다).
 */
class SessionPrincipalEmailMigrationTest extends IntegrationTest {

	private static final String MIGRATION_RESOURCE =
			"db/migration/app/V20260903080645__session_principal_email_to_user_id.sql";

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void 이메일_principal_세션은_userId_문자열로_이전되고_무관한_세션은_그대로다() throws Exception {
		String email = "legacy-session-" + UUID.randomUUID() + "@ex.com";
		Long userId = jdbcClient.sql("""
						INSERT INTO app.users (email, password_hash, name) VALUES (:email, 'h', '레거시')
						RETURNING id""")
				.param("email", email)
				.query(Long.class).single();

		String legacySessionId = insertSpringSession(email);
		String freshSessionId = insertSpringSession(String.valueOf(userId)); // 신규 방식(이미 userId)
		String unrelatedSessionId = insertSpringSession("not-an-email-or-id"); // 매칭 대상 아님

		String migrationSql = new ClassPathResource(MIGRATION_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
		jdbcClient.sql(migrationSql).update();

		assertThat(principalNameOf(legacySessionId)).isEqualTo(String.valueOf(userId));
		assertThat(principalNameOf(freshSessionId)).isEqualTo(String.valueOf(userId));
		assertThat(principalNameOf(unrelatedSessionId)).isEqualTo("not-an-email-or-id");
	}

	private String insertSpringSession(String principalName) {
		String primaryId = UUID.randomUUID().toString();
		String sessionId = UUID.randomUUID().toString();
		long now = System.currentTimeMillis();
		jdbcClient.sql("""
						INSERT INTO app.spring_session
						    (primary_id, session_id, creation_time, last_access_time,
						     max_inactive_interval, expiry_time, principal_name)
						VALUES (:primaryId, :sessionId, :now, :now, 2592000, :expiry, :principalName)""")
				.param("primaryId", primaryId)
				.param("sessionId", sessionId)
				.param("now", now)
				.param("expiry", now + 2592000_000L)
				.param("principalName", principalName)
				.update();
		return sessionId;
	}

	private String principalNameOf(String sessionId) {
		return jdbcClient.sql("SELECT principal_name FROM app.spring_session WHERE session_id = :sessionId")
				.param("sessionId", sessionId)
				.query(String.class)
				.single();
	}
}
