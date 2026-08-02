package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
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
 * springdoc 스모크 + admin 게이트(설계 2026-07-19) — 스웨거 표면은 ADMIN만, 매 요청 Basic 재인증.
 * 세션 쿠키는 무시되어야 한다(STATELESS) — 세션엔 로그인 시점 권한 스냅샷이 남아 강등이 반영 안 되기 때문.
 * 스키마 상세는 검증하지 않는다 — 정본은 프론트 API 스펙 문서, Swagger는 보조 문서.
 * 계정은 DB 직접 시드 — 가입 API 경유는 role 승격이 어차피 수동 SQL이라 우회 이득이 없다.
 */
@AutoConfigureMockMvc
class OpenApiDocsIntegrationTest extends IntegrationTest {

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "swagger-admin@test.io";
	private static final String USER_EMAIL = "swagger-user@test.io";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	@BeforeEach
	void seedUsers() {
		insertUser(ADMIN_EMAIL, "ADMIN");
		insertUser(USER_EMAIL, "USER");
	}

	/** 컨테이너는 JVM 공유(IntegrationTest) — 재실행 대비 ON CONFLICT 멱등 시드. */
	private void insertUser(String email, String role) {
		jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role)
				VALUES (:email, :hash, :role)
				ON CONFLICT (email) DO NOTHING""")
				.param("email", email)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.param("role", role)
				.update();
	}

	@Test
	void 익명은_401과_Basic_팝업_헤더를_받는다() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists("WWW-Authenticate"));
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists("WWW-Authenticate"));
	}

	@Test
	void 일반_USER는_403이다() throws Exception {
		mockMvc.perform(get("/v3/api-docs").with(httpBasic(USER_EMAIL, PASSWORD)))
				.andExpect(status().isForbidden());
	}

	@Test
	void yaml_문서와_html_진입점도_게이트_안이다() throws Exception {
		// /v3/api-docs.yaml은 /v3/api-docs/** 패턴 밖(세그먼트 매칭) — 매처 누락 시 메인 체인으로
		// 떨어져 세션 USER에게 전체 문서가 새는 우회가 실재했다. 명시 매처를 고정한다.
		mockMvc.perform(get("/v3/api-docs.yaml").with(httpBasic(USER_EMAIL, PASSWORD)))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists("WWW-Authenticate"));
	}

	@Test
	void ADMIN은_v1_표면만_담긴_문서를_본다() throws Exception {
		mockMvc.perform(get("/v3/api-docs").with(httpBasic(ADMIN_EMAIL, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("hypenow API"))
				.andExpect(jsonPath("$.paths['/v1/contents']").exists())
				// 구 /api 표면(레거시 /api/auth 등)·내부 페이지는 paths-to-match(/v1/**, /admin/**) 밖 — 문서에 없어야 한다
				.andExpect(jsonPath("$.paths['/api/auth/login']").doesNotExist());
	}

	@Test
	void 베이스_경로도_진입점이다() throws Exception {
		// /swagger-ui(베이스)는 시큐리티 매처(/swagger-ui/**)에는 잡히지만 springdoc 기본 매핑이 없어
		// 인증 통과 후 404(Whitelabel)가 났다 — 운영에서 ADMIN이 자연 URL로 들어오다 실제로 걸린 경로.
		// swagger-ui.path=/swagger-ui로 진입 리다이렉트를 매핑해 index.html로 보낸다.
		mockMvc.perform(get("/swagger-ui").with(httpBasic(ADMIN_EMAIL, PASSWORD)))
				.andExpect(status().is3xxRedirection());
		mockMvc.perform(get("/swagger-ui"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists("WWW-Authenticate"));
	}

	@Test
	void ADMIN_세션_쿠키만으로는_401이다() throws Exception {
		// STATELESS 불변식 — 세션의 권한 스냅샷(로그인 시점 authorities)이 스웨거 게이트에 통하면
		// 강등된 admin이 로그아웃 전까지 문서를 보게 된다. 세션 경로는 반드시 무시.
		MvcResult login = mockMvc.perform(post("/v1/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN_EMAIL, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn();
		Cookie session = login.getResponse().getCookie("hypenow-session");
		assertThat(session).isNotNull(); // 로그인 성공 확인 — null이면 아래가 NPE로 오도된다
		mockMvc.perform(get("/v3/api-docs").cookie(session))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists("WWW-Authenticate"));
	}

	/**
	 * 07-30 프론트 요청 A-2 — Map&lt;String,Object&gt; 바디라 스키마가 비던 5개 엔드포인트에
	 * {@code @RequestBody(content=@Content(schema=@Schema(implementation=...)))}로 문서 전용
	 * record(CampaignRequestDocs 등)를 붙였다. "붙였으니 나올 것"이 아니라 실제 생성된
	 * /v3/api-docs에 필드가 들어있는지 단언한다.
	 */
	@Test
	void POST_PATCH_5개_엔드포인트의_requestBody_스키마에_필드가_노출된다() throws Exception {
		mockMvc.perform(get("/v3/api-docs").with(httpBasic(ADMIN_EMAIL, PASSWORD)))
				.andExpect(status().isOk())
				// 캠페인 POST — seedingCount 포함, name이 required
				.andExpect(jsonPath(
						"$.paths['/v1/monitoring/campaigns'].post.requestBody.content['application/json'].schema['$ref']")
						.value("#/components/schemas/CampaignCreateRequest"))
				.andExpect(jsonPath("$.components.schemas.CampaignCreateRequest.properties.seedingCount").exists())
				.andExpect(jsonPath("$.components.schemas.CampaignCreateRequest.properties.budget").exists())
				.andExpect(jsonPath("$.components.schemas.CampaignCreateRequest.required", hasItem("name")))
				// 캠페인 PATCH — 같은 필드(seedingCount 포함), 전부 선택
				.andExpect(jsonPath(
						"$.paths['/v1/monitoring/campaigns/{campaignId}'].patch.requestBody.content['application/json'].schema['$ref']")
						.value("#/components/schemas/CampaignPatchRequest"))
				.andExpect(jsonPath("$.components.schemas.CampaignPatchRequest.properties.seedingCount").exists())
				// 모니터링 items POST — trackingDays·posts·accounts·keywords·campaignName
				.andExpect(jsonPath(
						"$.paths['/v1/monitoring/items'].post.requestBody.content['application/json'].schema['$ref']")
						.value("#/components/schemas/MonitoringRegisterRequest"))
				.andExpect(jsonPath("$.components.schemas.MonitoringRegisterRequest.properties.trackingDays").exists())
				.andExpect(jsonPath("$.components.schemas.MonitoringRegisterRequest.properties.posts").exists())
				.andExpect(jsonPath("$.components.schemas.MonitoringRegisterRequest.properties.keywords['$ref']")
						.value("#/components/schemas/MonitoringItemKeywords"))
				.andExpect(jsonPath("$.components.schemas.MonitoringRegisterRequest.required", hasItem("trackingDays")))
				// 모니터링 items PATCH — trackingDays·campaignName·campaignId
				.andExpect(jsonPath(
						"$.paths['/v1/monitoring/items/{itemId}'].patch.requestBody.content['application/json'].schema['$ref']")
						.value("#/components/schemas/MonitoringItemPatchRequest"))
				.andExpect(jsonPath("$.components.schemas.MonitoringItemPatchRequest.properties.campaignName").exists())
				// 알림 설정 PATCH — content(이벤트별 부분 맵)
				.andExpect(jsonPath(
						"$.paths['/v1/notification-settings'].patch.requestBody.content['application/json'].schema['$ref']")
						.value("#/components/schemas/NotificationSettingsPatchRequest"))
				.andExpect(
						jsonPath("$.components.schemas.NotificationSettingsPatchRequest.properties.content").exists());
	}

	/** status·mode·contentType·reasonCode가 프론트 분기용 허용값 목록(enum)으로 노출되는지 확인한다. */
	@Test
	void 응답의_상태값_필드가_enum_허용값_목록으로_노출된다() throws Exception {
		mockMvc.perform(get("/v3/api-docs").with(httpBasic(ADMIN_EMAIL, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.schemas.TrackingItemResponse.properties.status.enum",
						containsInAnyOrder("collecting", "detecting", "tracking", "not_uploaded", "ended", "hidden",
								"error")))
				.andExpect(jsonPath("$.components.schemas.TrackingItemResponse.properties.mode.enum",
						containsInAnyOrder("url", "account")))
				.andExpect(jsonPath("$.components.schemas.TrackedPostResponse.properties.contentType.enum",
						containsInAnyOrder("reels", "feed")))
				.andExpect(jsonPath("$.components.schemas.Entry.properties.reasonCode.enum",
						containsInAnyOrder("invalid_format", "not_found", "private_account", "share_link_unresolved",
								"duplicate", "internal_error", "canceled")))
				// result는 4종만 노출(트랙 LL 계약 노출 방식 결정) — DB엔 canceled까지 5종이 있지만
				// API에서 canceled는 failed로 접혀 나가 계약에는 등장하지 않는다.
				.andExpect(jsonPath("$.components.schemas.Entry.properties.result.enum",
						containsInAnyOrder("success", "failed", "duplicate", "pending")));
	}

	@Test
	void 어드민_API도_문서화된다() throws Exception {
		// 07-22 결정: paths-to-match에 /admin/** 추가 — 접근은 기존 ADMIN 게이트가 통제하므로 보안 변화 없음.
		mockMvc.perform(get("/v3/api-docs").with(httpBasic(ADMIN_EMAIL, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/admin/signups']").exists())
				.andExpect(jsonPath("$.paths['/admin/signup-codes/{code}']").exists());
	}
}
