package com.celfit.was.v1.notices;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;

import com.celfit.was.IntegrationTest;
import com.celfit.was.PiiTestSeed;
import com.celfit.was.V1AuthTestSteps;
import com.celfit.was.crypto.FieldCipher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 어드민 업데이트 소식 CRUD(POST·GET·PATCH·DELETE /v1/admin/notices) — 실 DB로 검증 400 5종·예약분
 * 포함·전체 교체·404·인가 게이트를 확인한다. app.notices·notice_items는 V1NoticesIntegrationTest와
 * 같은 테이블을 쓰지만 이 클래스도 각자 @BeforeEach TRUNCATE라 충돌 없다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "was.rate-limit.per-minute=100")
// signup 레이트리밋 키가 "signup:" + IP(이메일 무관)라, 테스트 메서드마다 다른 이메일로 가입해도
// 같은 버킷을 공유해 기본 상한(10/분)에 부딪힌다(AdminQueryApiIntegrationTest와 동일 원인·동일 처방).
class AdminNoticesIntegrationTest extends IntegrationTest {

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "notices-admin@test.io";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	FieldCipher fieldCipher;

	private Cookie adminSession;

	@BeforeEach
	void setUp() throws Exception {
		V1AuthTestSteps.enableSignupCode(jdbcClient);
		jdbcClient.sql("TRUNCATE app.notices RESTART IDENTITY CASCADE").update();
		jdbcClient.sql("TRUNCATE app.notice_seen").update();
		jdbcClient.sql("DELETE FROM app.users WHERE email = :email").param("email", ADMIN_EMAIL).update();
		insertAdmin(ADMIN_EMAIL);
		adminSession = login(ADMIN_EMAIL);
	}

	private String createBody(String publishedAt) {
		return """
				{"title":"8월 업데이트","publishedAt":"%s","items":[
				  {"tag":"new","summary":"새 기능","body":"본문1","link":{"href":"https://a","label":"자세히"}},
				  {"tag":"fixed","summary":"버그 수정","body":"","link":null}
				]}
				""".formatted(publishedAt);
	}

	@Test
	void POST는_201로_생성하고_date_KST_파생과_publishedAt_KST_회신과_items_순서와_link_null을_지킨다() throws Exception {
		mockMvc.perform(post("/v1/admin/notices").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("2026-08-03T09:00")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").exists())
				.andExpect(jsonPath("$.data.title").value("8월 업데이트"))
				.andExpect(jsonPath("$.data.date").value("2026-08-03"))
				.andExpect(jsonPath("$.data.publishedAt").value("2026-08-03T09:00:00+09:00"))
				.andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.items[0].tag").value("new"))
				.andExpect(jsonPath("$.data.items[0].summary").value("새 기능"))
				.andExpect(jsonPath("$.data.items[0].link.href").value("https://a"))
				.andExpect(jsonPath("$.data.items[0].link.label").value("자세히"))
				.andExpect(jsonPath("$.data.items[1].tag").value("fixed"))
				.andExpect(jsonPath("$.data.items[1].body").value(""))
				.andExpect(jsonPath("$.data.items[1].link").value(nullValue()));
	}

	@Test
	void 검증_6종은_400이다() throws Exception {
		assertValidation("""
				{"title":"  ","publishedAt":"2026-08-03T09:00","items":[
				  {"tag":"new","summary":"요약","body":"본문","link":null}]}
				""");
		assertValidation("""
				{"title":"제목","publishedAt":"2026-08-03T09:00","items":[]}
				""");
		assertValidation("""
				{"title":"제목","publishedAt":"2026-08-03T09:00","items":[
				  {"tag":"new","summary":"  ","body":"본문","link":null}]}
				""");
		assertValidation("""
				{"title":"제목","publishedAt":"2026-08-03T09:00","items":[
				  {"tag":"broken","summary":"요약","body":"본문","link":null}]}
				""");
		assertValidation("""
				{"title":"제목","publishedAt":"2026-08-03T09:00","items":[
				  {"tag":"new","summary":"요약","body":"본문","link":{"href":"https://a","label":"  "}}]}
				""");
		// 리뷰 결함: items 배열에 null 원소가 오면 item.summary()에서 NPE → 500이었다.
		assertValidation("""
				{"title":"제목","publishedAt":"2026-08-03T09:00","items":[null]}
				""");
	}

	private void assertValidation(String body) throws Exception {
		mockMvc.perform(post("/v1/admin/notices").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 어드민_목록은_예약분을_포함하고_유저_목록은_예약분을_제외한다() throws Exception {
		mockMvc.perform(post("/v1/admin/notices").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("2020-01-01T09:00")))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/v1/admin/notices").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("2999-01-01T09:00")))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/v1/admin/notices").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.total").value(2))
				.andExpect(jsonPath("$.data.length()").value(2));

		Cookie userSession = V1AuthTestSteps.signUp(mockMvc, jdbcClient, "notices-cross-check@example.com");
		mockMvc.perform(get("/v1/notices").cookie(userSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.total").value(1))
				.andExpect(jsonPath("$.data.length()").value(1));
	}

	@Test
	void PATCH는_items를_전체_교체하고_재조회에_반영되며_부재시_404이고_비숫자_id도_404다() throws Exception {
		MvcResult created = mockMvc.perform(post("/v1/admin/notices").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("2026-08-03T09:00")))
				.andExpect(status().isCreated())
				.andReturn();
		String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.data.id")
				.toString();

		String patchBody = """
				{"title":"수정된 제목","publishedAt":"2026-08-04T10:00","items":[
				  {"tag":"fixed","summary":"뒤집힌 항목B","body":"본문B","link":null},
				  {"tag":"new","summary":"뒤집힌 항목A","body":"본문A","link":{"href":"https://b","label":"보기"}},
				  {"tag":"improved","summary":"항목C","body":"본문C","link":null}
				]}
				""";

		mockMvc.perform(patch("/v1/admin/notices/" + id).with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content(patchBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.title").value("수정된 제목"))
				.andExpect(jsonPath("$.data.publishedAt").value("2026-08-04T10:00:00+09:00"))
				.andExpect(jsonPath("$.data.items.length()").value(3))
				.andExpect(jsonPath("$.data.items[0].summary").value("뒤집힌 항목B"))
				.andExpect(jsonPath("$.data.items[1].summary").value("뒤집힌 항목A"))
				.andExpect(jsonPath("$.data.items[2].summary").value("항목C"));

		mockMvc.perform(get("/v1/admin/notices").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].items.length()").value(3))
				.andExpect(jsonPath("$.data[0].items[0].summary").value("뒤집힌 항목B"));

		mockMvc.perform(patch("/v1/admin/notices/999999999").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content(patchBody))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		mockMvc.perform(patch("/v1/admin/notices/not-a-number").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content(patchBody))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void DELETE는_204이고_목록에서_사라지며_부재시_404다() throws Exception {
		MvcResult created = mockMvc.perform(post("/v1/admin/notices").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("2026-08-03T09:00")))
				.andExpect(status().isCreated())
				.andReturn();
		String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.data.id")
				.toString();

		mockMvc.perform(delete("/v1/admin/notices/" + id).with(csrf()).cookie(adminSession))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/v1/admin/notices").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.total").value(0));

		mockMvc.perform(delete("/v1/admin/notices/" + id).with(csrf()).cookie(adminSession))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 일반_유저는_403이고_비로그인은_401이다() throws Exception {
		Cookie userSession = V1AuthTestSteps.signUp(mockMvc, jdbcClient, "notices-forbidden@example.com");

		mockMvc.perform(post("/v1/admin/notices").with(csrf()).cookie(userSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("2026-08-03T09:00")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		mockMvc.perform(post("/v1/admin/notices").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody("2026-08-03T09:00")))
				.andExpect(status().isUnauthorized());
	}

	private long insertAdmin(String email) {
		jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type, signup_route,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, :hash, 'ADMIN', '관리자', 'brand', 'portal_search', true, true, true)
				ON CONFLICT (email) DO UPDATE SET role = 'ADMIN'""")
				.param("email", email)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.update();
		PiiTestSeed.backfill(jdbcClient, fieldCipher);
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
}
