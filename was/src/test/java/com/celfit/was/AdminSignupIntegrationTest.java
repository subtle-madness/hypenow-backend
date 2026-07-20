package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
 * 실 DB에 유저·코드를 시드해 인증 경계와 소진/미소진 정렬을 검증한다.
 * 싱글턴 DB 공유·무롤백이라 전역 위치 대신 코드 값 필터·상대 인덱스로 단언한다.
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
		seedUser("user-403@x.com", "USER");
		mockMvc.perform(get("/admin/signups").with(httpBasic("user-403@x.com", "Passw0rd!")))
				.andExpect(status().isForbidden());
	}

	@Test
	void ADMIN이면_소진코드는_유저와_미소진코드는_null로_반환() throws Exception {
		long memberId = seedUser("member@x.com", "USER");
		seedUser("admin@x.com", "ADMIN");
		seedUsedCode("THREADS-USED", "THREADS", memberId);
		seedUnusedCode("DM-OPEN", "DM");

		// HTTP 계약: 200 + 소진 코드의 email 채워짐, 미소진 코드의 email 키가 명시적 null로 존재.
		// 필터([?()])는 indefinite path라 결과가 리스트 → contains 매처로 단언.
		mockMvc.perform(get("/admin/signups").with(httpBasic("admin@x.com", "Passw0rd!")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code=='THREADS-USED')].email")
						.value(org.hamcrest.Matchers.contains("member@x.com")))
				.andExpect(jsonPath("$[?(@.code=='DM-OPEN')].email")
						.value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));

		// 필드·정렬 정밀 단언은 리포지토리 반환에서(위치 무관, null 명확).
		// 소진(THREADS-USED)이 미소진(DM-OPEN)보다 앞 — NULLS LAST는 전역에서 항상 성립.
		List<SignupUsageRow> rows = repository.findAll();
		SignupUsageRow used = rows.stream().filter(r -> r.code().equals("THREADS-USED")).findFirst().orElseThrow();
		assertThat(used.email()).isEqualTo("member@x.com");
		assertThat(used.channel()).isEqualTo("THREADS");
		assertThat(used.userId()).isEqualTo(memberId);
		assertThat(used.usedAt()).isNotNull();
		SignupUsageRow open = rows.stream().filter(r -> r.code().equals("DM-OPEN")).findFirst().orElseThrow();
		assertThat(open.email()).isNull();
		assertThat(open.userId()).isNull();
		assertThat(open.usedAt()).isNull();
		assertThat(indexOfCode(rows, "THREADS-USED")).isLessThan(indexOfCode(rows, "DM-OPEN"));
	}
}
