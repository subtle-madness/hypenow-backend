package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import com.celfit.was.admin.AdminSignupRepository;
import com.celfit.was.admin.SignupUsageRow;

/**
 * 관리자 가입 코드 조회(설계 2026-07-19) — /admin/**는 ADMIN Basic 체인(SecurityConfig @Order(1)).
 * 실 DB에 유저·코드를 시드해 인증 경계와 소진/미소진/탈퇴 정렬을 검증한다.
 * 싱글턴 DB 공유·무롤백이라 전역 위치 대신 코드 값 필터·상대 인덱스로 단언하고,
 * 재실행 충돌을 피하려 이메일·코드는 매 호출 UUID로 유니크하게 발급한다(OpenApiDocsIntegrationTest의
 * ON CONFLICT 멱등 시드와 달리 여기선 seedUser가 RETURNING id로 PK를 받아야 해서 유니크 값 방식을 쓴다).
 */
@AutoConfigureMockMvc
class AdminSignupIntegrationTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	AdminSignupRepository repository;

	/** 재실행 대비 유니크 이메일 — seedUser는 INSERT ... RETURNING id라 ON CONFLICT로는 못 우회한다. */
	private String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID() + "@x.com";
	}

	/** 재실행 대비 유니크 코드 — code는 signup_codes의 PK. */
	private String uniqueCode(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	private long seedUser(String email, String role) {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role) VALUES (:email, :hash, :role)
				RETURNING id""")
				.param("email", email)
				.param("hash", passwordEncoder.encode("Passw0rd!"))
				.param("role", role)
				.query(Long.class)
				.single();
	}

	private void deleteUser(long userId) {
		jdbcClient.sql("DELETE FROM app.users WHERE id = :userId")
				.param("userId", userId)
				.update();
	}

	private void seedUsedCode(String code, String channel, long userId) {
		jdbcClient.sql("""
				INSERT INTO app.signup_codes (code, channel, used_by, used_at)
				VALUES (:code, :channel, :userId, now())""")
				.param("code", code)
				.param("channel", channel)
				.param("userId", userId)
				.update();
	}

	private void seedUnusedCode(String code, String channel) {
		jdbcClient.sql("INSERT INTO app.signup_codes (code, channel) VALUES (:code, :channel)")
				.param("code", code)
				.param("channel", channel)
				.update();
	}

	private int indexOfCode(List<SignupUsageRow> rows, String code) {
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).code().equals(code)) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void 미인증이면_401() throws Exception {
		mockMvc.perform(get("/admin/signups"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void ADMIN_아니면_403() throws Exception {
		String userEmail = uniqueEmail("user-403");
		seedUser(userEmail, "USER");
		mockMvc.perform(get("/admin/signups").with(httpBasic(userEmail, "Passw0rd!")))
				.andExpect(status().isForbidden());
	}

	@Test
	void ADMIN이면_소진코드는_유저와_미소진코드는_null로_반환() throws Exception {
		String memberEmail = uniqueEmail("member");
		String adminEmail = uniqueEmail("admin");
		String usedCode = uniqueCode("THREADS-USED");
		String openCode = uniqueCode("DM-OPEN");
		long memberId = seedUser(memberEmail, "USER");
		seedUser(adminEmail, "ADMIN");
		seedUsedCode(usedCode, "THREADS", memberId);
		seedUnusedCode(openCode, "DM");

		// HTTP 계약: 200 + 소진 코드의 email 채워짐, 미소진 코드의 email 키가 명시적 null로 존재.
		// 필터([?()])는 indefinite path라 결과가 리스트 → contains 매처로 단언.
		mockMvc.perform(get("/admin/signups").with(httpBasic(adminEmail, "Passw0rd!")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code=='" + usedCode + "')].email")
						.value(org.hamcrest.Matchers.contains(memberEmail)))
				.andExpect(jsonPath("$[?(@.code=='" + openCode + "')].email")
						.value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));

		// 필드·정렬 정밀 단언은 리포지토리 반환에서(위치 무관, null 명확).
		// 소진(usedCode)이 미소진(openCode)보다 앞 — NULLS LAST는 전역에서 항상 성립.
		List<SignupUsageRow> rows = repository.findAll();
		SignupUsageRow used = rows.stream().filter(r -> r.code().equals(usedCode)).findFirst().orElseThrow();
		assertThat(used.email()).isEqualTo(memberEmail);
		assertThat(used.channel()).isEqualTo("THREADS");
		assertThat(used.userId()).isEqualTo(memberId);
		assertThat(used.usedAt()).isNotNull();
		SignupUsageRow open = rows.stream().filter(r -> r.code().equals(openCode)).findFirst().orElseThrow();
		assertThat(open.email()).isNull();
		assertThat(open.userId()).isNull();
		assertThat(open.usedAt()).isNull();
		assertThat(indexOfCode(rows, usedCode)).isLessThan(indexOfCode(rows, openCode));
	}

	@Test
	void 소진코드_사용자가_탈퇴하면_email과_userId만_null이고_usedAt은_유지된다() {
		String deletedEmail = uniqueEmail("withdrawn");
		String withdrawnCode = uniqueCode("DM-WITHDRAWN");
		long userId = seedUser(deletedEmail, "USER");
		seedUsedCode(withdrawnCode, "DM", userId);

		deleteUser(userId); // used_by는 ON DELETE SET NULL — used_at(소진 정본)은 그대로 남는다(V8 주석).

		List<SignupUsageRow> rows = repository.findAll();
		SignupUsageRow withdrawn = rows.stream().filter(r -> r.code().equals(withdrawnCode)).findFirst().orElseThrow();
		assertThat(withdrawn.email()).isNull();
		assertThat(withdrawn.userId()).isNull();
		assertThat(withdrawn.usedAt()).isNotNull();
	}

	@Test
	void 새로_적재된_코드는_isSent가_false다() throws Exception {
		String adminEmail = uniqueEmail("admin-issent");
		seedUser(adminEmail, "ADMIN");
		String code = uniqueCode("DM-ISSENT");
		seedUnusedCode(code, "DM");

		// HTTP 계약: 조회 응답에 isSent 키가 false로 존재.
		mockMvc.perform(get("/admin/signups").with(httpBasic(adminEmail, "Passw0rd!")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code=='" + code + "')].isSent")
						.value(org.hamcrest.Matchers.contains(false)));

		SignupUsageRow row = repository.findAll().stream()
				.filter(r -> r.code().equals(code)).findFirst().orElseThrow();
		assertThat(row.isSent()).isFalse();
	}
}
