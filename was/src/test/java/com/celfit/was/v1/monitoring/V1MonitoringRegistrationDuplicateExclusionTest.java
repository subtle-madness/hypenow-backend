package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.monitoring.RegistrationRow;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * B-2(플랜 갭 문서) — 6.27 중복 판정의 자연 종결 제외. target이 확정된 행이라도 유도 상태가
 * 종결 3종(not_uploaded/ended/hidden)이면 재등록을 허용해야 한다(계약: "종결 상태와의 중복은
 * 허용(재등록)"). 이 검증은 target 조회 표면(monitoring DB)이 필요해 기본 IntegrationTest의
 * readRepository=Optional.empty() 배선으로는 재현이 안 되므로, V1MonitoringItemUpdateIntegrationTest와
 * 같은 방식으로 monitoring-schema.sql을 같은 컨테이너의 public 스키마에 적용하고 서비스를 직접
 * 생성해 실 target 조회를 태운다.
 */
class V1MonitoringRegistrationDuplicateExclusionTest extends IntegrationTest {

	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	RegistrationRepository registrationRepository;
	@Autowired
	CampaignRepository campaignRepository;
	@Autowired
	DataSource dataSource;
	@Autowired
	JdbcClient jdbcClient;

	JdbcClient monitoringJdbc;
	V1MonitoringRegistrationService service;
	long userId;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
		}
		monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot RESTART IDENTITY")
				.update();

		MonitoringReadRepository readRepository = new MonitoringReadRepository(monitoringJdbc);
		V1CampaignService campaignService = new V1CampaignService(campaignRepository);
		service = new V1MonitoringRegistrationService(itemRepository, registrationRepository, campaignRepository,
				campaignService, mock(RegistrationExecutor.class), new ObjectMapper(), Optional.of(readRepository));

		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-reg-dup-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	private static Map<String, Object> body(List<String> posts, List<String> accounts, Map<String, Object> keywords,
			int trackingDays) {
		Map<String, Object> map = new HashMap<>();
		map.put("posts", posts);
		map.put("accounts", accounts);
		if (keywords != null) {
			map.put("keywords", keywords);
		}
		map.put("trackingDays", trackingDays);
		return map;
	}

	private long seedTarget(String username, String shortCode, String status, String trackedShortCode) {
		return monitoringJdbc.sql("""
				INSERT INTO target (type, username, short_code, status, tracked_short_code, registration_key, expires_at)
				VALUES ('ACCOUNT', :username, :shortCode, :status, :tracked, gen_random_uuid()::text,
				        now() + interval '30 days')
				RETURNING id
				""")
				.param("username", username).param("shortCode", shortCode).param("status", status)
				.param("tracked", trackedShortCode)
				.query(Long.class).single();
	}

	private String resultOf(String registrationId) {
		long id = Long.parseLong(registrationId);
		List<RegistrationEntryRow> entries = registrationRepository.findRecentByUser(userId, 50).stream()
				.filter(row -> row.id() == id)
				.findFirst()
				.map(RegistrationRow::entries)
				.orElseThrow();
		return entries.get(0).result();
	}

	@Test
	void EXPIRED_미업로드_행과는_중복이_아니라_재등록이_허용된다() {
		long targetId = seedTarget("glowdeep", null, "EXPIRED", null);   // 유도 status = not_uploaded
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, LocalDate.now().minusDays(30));
		itemRepository.confirmTarget(itemId, targetId);

		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of(), List.of("glowdeep"), Map.of("and", List.of("A"), "or", List.of(), "exclude", List.of()), 14));

		assertThat(resultOf(response.registrationId())).isEqualTo("pending");
	}

	@Test
	void EXPIRED_추적게시물_있는_ended_행과는_중복이_아니라_재등록이_허용된다() {
		long targetId = seedTarget("glowdeep", null, "EXPIRED", "SHORT1");   // 유도 status = ended
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "SHORT1",
				"https://www.instagram.com/p/SHORT1/", null, 14, LocalDate.now().minusDays(30));
		itemRepository.confirmTarget(itemId, targetId);

		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of("https://www.instagram.com/p/SHORT1/"), List.of(), null, 14));

		assertThat(resultOf(response.registrationId())).isEqualTo("pending");
	}

	@Test
	void hidden_행과는_중복이_아니라_재등록이_허용된다() {
		long targetId = monitoringJdbc.sql("""
				INSERT INTO target (type, username, status, tracked_short_code, registration_key, expires_at,
				                    tracked_hidden_at)
				VALUES ('ACCOUNT', 'glowdeep', 'TRACKING', 'SHORT1', gen_random_uuid()::text,
				        now() + interval '30 days', now())
				RETURNING id
				""").query(Long.class).single();
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, LocalDate.now());
		itemRepository.confirmTarget(itemId, targetId);

		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of(), List.of("glowdeep"), Map.of("and", List.of("A"), "or", List.of(), "exclude", List.of()), 14));

		assertThat(resultOf(response.registrationId())).isEqualTo("pending");
	}

	@Test
	void error_행은_진행_중이라_여전히_duplicate다() {
		long targetId = monitoringJdbc.sql("""
				INSERT INTO target (type, username, status, tracked_short_code, registration_key, expires_at,
				                    fetch_failing)
				VALUES ('ACCOUNT', 'glowdeep', 'TRACKING', 'SHORT1', gen_random_uuid()::text,
				        now() + interval '30 days', true)
				RETURNING id
				""").query(Long.class).single();
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, LocalDate.now());
		itemRepository.confirmTarget(itemId, targetId);

		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of(), List.of("glowdeep"), Map.of("and", List.of("A"), "or", List.of(), "exclude", List.of()), 14));

		assertThat(resultOf(response.registrationId())).isEqualTo("duplicate");
	}

	@Test
	void TRACKING_진행중_행은_여전히_duplicate다() {
		long targetId = seedTarget("glowdeep", null, "TRACKING", "SHORT1");
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, LocalDate.now());
		itemRepository.confirmTarget(itemId, targetId);

		MonitoringRegistrationResponse response = service.register(userId,
				body(List.of(), List.of("glowdeep"), Map.of("and", List.of("A"), "or", List.of(), "exclude", List.of()), 14));

		assertThat(resultOf(response.registrationId())).isEqualTo("duplicate");
	}
}
