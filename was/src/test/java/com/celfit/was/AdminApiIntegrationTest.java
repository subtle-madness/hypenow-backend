package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 어드민 백엔드 API P0(설계 2026-08-01 §1·§2·§3) 실 DB·실 필터체인 검증 —
 * /v1/admin/** 게이트, X-Act-As-User 사칭, last_active_at 갱신.
 * 계정은 DB 직접 시드 후 /v1/auth/login으로 세션 획득(OpenApiDocsIntegrationTest 관용구) —
 * 로그인 시점에 AppUserDetailsService가 DB role을 새로 읽으므로 세션 스냅샷이 정확하다.
 * /v1/admin/ping은 실 어드민 컨트롤러가 아직 없어 게이트·act-as "무시" 분기 검증용으로 둔
 * 임시 엔드포인트(AdminPingController) — 다음 에이전트가 실 조회 API를 얹는다.
 */
@AutoConfigureMockMvc
class AdminApiIntegrationTest extends IntegrationTest {

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "admin-p0@test.io";
	private static final String USER_EMAIL = "user-p0@test.io";
	private static final String TARGET_EMAIL = "target-p0@test.io";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	private long adminId;
	private long userId;
	private long targetId;

	@BeforeEach
	void seedUsers() {
		adminId = insertUser(ADMIN_EMAIL, "ADMIN");
		userId = insertUser(USER_EMAIL, "USER");
		targetId = insertUser(TARGET_EMAIL, "USER");
	}

	/**
	 * 컨테이너는 JVM 공유(IntegrationTest) — 재실행 대비 ON CONFLICT 멱등 시드, id는 항상 재조회.
	 * last_active_at도 매번 NULL로 되돌린다 — 이전 테스트 메서드의 GET /v1/me가 이미 채워 놨을 수 있어
	 * §3 테스트가 실행 순서에 흔들리지 않게 한다.
	 */
	private long insertUser(String email, String role) {
		jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type, agreed_terms,
				                       agreed_privacy, agreed_age14)
				VALUES (:email, :hash, :role, '테스트', 'brand', true, true, true)
				ON CONFLICT (email) DO UPDATE SET role = :role, last_active_at = NULL""")
				.param("email", email)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.param("role", role)
				.update();
		return jdbcClient.sql("SELECT id FROM app.users WHERE email = :email")
				.param("email", email)
				.query(Long.class)
				.single();
	}

	private Cookie login(String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn();
		Cookie session = result.getResponse().getCookie("hypenow-session");
		assertThat(session).isNotNull();
		return session;
	}

	// --- §1 /v1/admin/** 게이트 ---

	@Test
	void 미인증은_401_envelope다() throws Exception {
		mockMvc.perform(get("/v1/admin/ping"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void 일반_유저는_403_FORBIDDEN_envelope다() throws Exception {
		Cookie session = login(USER_EMAIL);

		mockMvc.perform(get("/v1/admin/ping").cookie(session))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void 어드민은_200이다() throws Exception {
		Cookie session = login(ADMIN_EMAIL);

		mockMvc.perform(get("/v1/admin/ping").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ok").value(true));
	}

	// --- §2 X-Act-As-User ---

	@Test
	void 어드민_GET에_헤더가_있으면_대상_유저_기준_응답과_감사기록을_남긴다() throws Exception {
		Cookie adminSession = login(ADMIN_EMAIL);

		mockMvc.perform(get("/v1/me").cookie(adminSession)
						.header("X-Act-As-User", String.valueOf(targetId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(targetId)))
				.andExpect(jsonPath("$.data.email").value(TARGET_EMAIL));

		Long auditCount = jdbcClient.sql("""
				SELECT count(*) FROM app.admin_audit_logs
				WHERE admin_id = :adminId AND target_user_id = :targetId AND path = '/v1/me'""")
				.param("adminId", adminId)
				.param("targetId", targetId)
				.query(Long.class)
				.single();
		assertThat(auditCount).isEqualTo(1L);
	}

	@Test
	void 일반_유저의_헤더는_무시되고_본인_응답이_그대로_나온다() throws Exception {
		Cookie userSession = login(USER_EMAIL);

		mockMvc.perform(get("/v1/me").cookie(userSession)
						.header("X-Act-As-User", String.valueOf(targetId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(userId)))
				.andExpect(jsonPath("$.data.email").value(USER_EMAIL));

		// 다른 테스트가 같은 targetId로 정상 act-as 감사 기록을 이미 남겼을 수 있어(실행 순서 무관하게
		// 안전하려면) "이 비어드민 유저가 기록을 남기지 않았다"만 admin_id로 좁혀 확인한다.
		Long auditCount = jdbcClient.sql("""
				SELECT count(*) FROM app.admin_audit_logs WHERE admin_id = :userId AND target_user_id = :targetId""")
				.param("userId", userId)
				.param("targetId", targetId)
				.query(Long.class)
				.single();
		assertThat(auditCount).isZero();
	}

	@Test
	void 어드민이_쓰기_메서드에_헤더를_쓰면_405와_Allow_헤더와_FORBIDDEN이다() throws Exception {
		Cookie adminSession = login(ADMIN_EMAIL);

		mockMvc.perform(patch("/v1/me").cookie(adminSession).with(csrf())
						.header("X-Act-As-User", String.valueOf(targetId))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"바뀐이름\"}"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Allow", "GET, HEAD"))
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.error.message").value("유저 뷰는 조회 전용이에요."));
	}

	@Test
	void 부재_유저_대상은_404_NOT_FOUND다() throws Exception {
		Cookie adminSession = login(ADMIN_EMAIL);

		mockMvc.perform(get("/v1/me").cookie(adminSession)
						.header("X-Act-As-User", "999999999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	/**
	 * NUMERIC_ID(^[0-9]+$)는 자릿수 무제한이라 Long.MAX_VALUE 초과 숫자 문자열도 통과시킨다 — 방어 없으면
	 * Long.parseLong이 NumberFormatException을 던져 필터 밖으로 전파되고 envelope 깨진 Boot 기본 에러
	 * 응답이 나간다(코드 리뷰 지적). AdminUsersController.parseId와 동일한 try/catch로 404에 흡수한다.
	 */
	@Test
	void Long_오버플로우_대상_id는_전파되지_않고_404_NOT_FOUND다() throws Exception {
		Cookie adminSession = login(ADMIN_EMAIL);

		mockMvc.perform(get("/v1/me").cookie(adminSession)
						.header("X-Act-As-User", "99999999999999999999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 어드민_표면으로의_헤더는_무시하고_어드민_본인_기준으로_동작한다() throws Exception {
		Cookie adminSession = login(ADMIN_EMAIL);

		// /v1/admin/** 자체는 사칭 의미가 없다 — 헤더가 있어도 정상 어드민 응답(200)을 그대로 받는다.
		mockMvc.perform(get("/v1/admin/ping").cookie(adminSession)
						.header("X-Act-As-User", String.valueOf(targetId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ok").value(true));
	}

	// --- §1·§2 세션 authority 신선도 재확인(코드 리뷰 지적) ---

	/**
	 * 세션엔 로그인 시점 authorities가 그대로 남는다(AppUserDetails 클래스 주석) — 로그인 후 DB에서
	 * role을 USER로 강등해도 같은 세션 쿠키는 로그아웃 전까지 ROLE_ADMIN을 계속 주장한다. 두 어드민
	 * 판정(AdminRoleFreshnessFilter의 /v1/admin/** 게이트, ActAsUserFilter.isAdmin)이 매 요청 DB
	 * role을 재확인하는지 검증한다.
	 */
	@Test
	void 로그인_후_DB에서_강등되면_admin_게이트는_403이고_act_as_헤더는_무시된다() throws Exception {
		Cookie adminSession = login(ADMIN_EMAIL);

		jdbcClient.sql("UPDATE app.users SET role = 'USER' WHERE id = :id").param("id", adminId).update();

		mockMvc.perform(get("/v1/admin/users").cookie(adminSession))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		// act-as 헤더도 무시되고 (강등된) 본인 기준 응답이 그대로 나온다 — 대상 유저로 스왑되지 않는다.
		mockMvc.perform(get("/v1/me").cookie(adminSession)
						.header("X-Act-As-User", String.valueOf(targetId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(adminId)))
				.andExpect(jsonPath("$.data.email").value(ADMIN_EMAIL));
	}

	// --- §3 last_active_at ---

	/**
	 * LastActiveAtFilter의 5분 스로틀 맵은 공유 컨텍스트(IntegrationTest 싱글턴 빈)에서 테스트 메서드
	 * 간에도 살아있다 — USER_EMAIL은 다른 테스트가 이미 GET /v1/me로 건드려 놨을 수 있어(그 경우 DB
	 * 컬럼만 NULL로 되돌려도 맵 때문에 이번 요청이 스킵된다), 이 테스트 전용 유저를 새로 심는다.
	 */
	@Test
	void 인증된_요청_후_last_active_at이_채워진다() throws Exception {
		String email = "throttle-p0@test.io";
		insertUser(email, "USER");
		assertThat(lastActiveAt(email)).isNull();

		Cookie session = login(email);
		mockMvc.perform(get("/v1/me").cookie(session)).andExpect(status().isOk());

		assertThat(lastActiveAt(email)).isNotNull();
	}

	private OffsetDateTime lastActiveAt(String email) {
		return jdbcClient.sql("SELECT last_active_at FROM app.users WHERE email = :email")
				.param("email", email)
				.query(OffsetDateTime.class)
				.optional()
				.orElse(null);
	}
}
