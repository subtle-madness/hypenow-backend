package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.monitoring.RegistrationRow;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * V1MonitoringItemUpdateService — 6.29(PATCH)·6.30(cancel)의 monitoring 연동을 검증한다. app DB는
 * IntegrationTest 실 컨테이너(리포지토리 왕복까지 확인), monitoring 서버는 MockRestServiceServer로
 * 대역한다. monitoring 조회 표면(target 등)은 MonitoringReadRepositoryTest와 동일하게
 * monitoring-schema.sql 픽스처를 같은 컨테이너의 public 스키마에 적용해 흉내 낸다(app.* 과 스키마로
 * 분리돼 있어 공존 가능).
 */
class V1MonitoringItemUpdateIntegrationTest extends IntegrationTest {

	static final String BASE = "http://monitoring:8083";

	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	CampaignRepository campaignRepository;
	@Autowired
	DataSource dataSource;
	@Autowired
	JdbcClient jdbcClient;

	MockRestServiceServer server;
	V1MonitoringItemUpdateService service;
	RegistrationRepository registrationRepository;
	JdbcClient monitoringJdbc;
	long userId;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
		}
		monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot RESTART IDENTITY")
				.update();

		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		MonitoringCommandClient commandClient = new MonitoringCommandClient(builder.build());
		MonitoringReadRepository readRepository = new MonitoringReadRepository(monitoringJdbc);
		V1CampaignService campaignService = new V1CampaignService(campaignRepository);
		TrackingItemAssembler assembler = new TrackingItemAssembler(itemRepository, campaignRepository,
				Optional.of(readRepository), new ObjectMapper());
		registrationRepository = new RegistrationRepository(jdbcClient);
		service = new V1MonitoringItemUpdateService(itemRepository, campaignRepository, campaignService,
				Optional.of(readRepository), Optional.of(commandClient), assembler, registrationRepository);

		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-update-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	private long seedTarget(String username, String status, String trackedShortCode) {
		return monitoringJdbc.sql("""
				INSERT INTO target (type, username, status, tracked_short_code, registration_key, expires_at)
				VALUES ('ACCOUNT', :username, :status, :tracked, gen_random_uuid()::text, now() + interval '30 days')
				RETURNING id
				""")
				.param("username", username).param("status", status).param("tracked", trackedShortCode)
				.query(Long.class).single();
	}

	@Test
	void PATCH_target_확정_행은_연장_호출_바디에_새_종료일을_담고_app을_갱신한다() {
		long targetId = seedTarget("glowdeep", "TRACKING", "SHORT1");
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST);
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, registeredOn);
		itemRepository.confirmTarget(itemId, targetId);

		LocalDate newEndDate = registeredOn.plusDays(30);
		server.expect(requestTo(BASE + "/api/targets/" + targetId))
				.andExpect(method(HttpMethod.PATCH))
				.andExpect(jsonPath("$.expiresAt", Matchers.startsWith(newEndDate.toString())))
				.andRespond(withSuccess(
						"{ \"targetId\": " + targetId + ", \"expiresAt\": \"" + newEndDate + "T00:00:00+09:00\" }",
						MediaType.APPLICATION_JSON));

		service.patch(userId, itemId, Map.of("trackingDays", 30));

		MonitoringItemRow updated = itemRepository.findByIdAndUser(itemId, userId).orElseThrow();
		assertThat(updated.trackingDays()).isEqualTo(30);
		server.verify();
	}

	@Test
	void PATCH_pending_행은_monitoring을_호출하지_않고_app만_갱신한다() {
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST);
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, registeredOn);
		// server에 어떤 expect()도 등록하지 않는다 — monitoring으로 요청이 나가면 MockRestServiceServer가
		// 즉시 AssertionError를 던져 이 테스트를 실패시킨다(암묵적 "무호출" 검증).

		service.patch(userId, itemId, Map.of("trackingDays", 45));

		assertThat(itemRepository.findByIdAndUser(itemId, userId).orElseThrow().trackingDays()).isEqualTo(45);
		server.verify();
	}

	@Test
	void cancel_target_확정_tracking_행은_monitoring_cancel_후_app에_취소가_반영된다() {
		long targetId = seedTarget("glowdeep", "TRACKING", "SHORT1");
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, LocalDate.now(KstTimestamps.KST));
		itemRepository.confirmTarget(itemId, targetId);

		server.expect(requestTo(BASE + "/api/targets/" + targetId))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withSuccess("{ \"targetId\": " + targetId + ", \"status\": \"CANCELED\" }",
						MediaType.APPLICATION_JSON));

		TrackingItemResponse response = service.cancel(userId, itemId);

		assertThat(response.status()).isEqualTo("ended");
		MonitoringItemRow row = itemRepository.findByIdAndUser(itemId, userId).orElseThrow();
		assertThat(row.canceledAt()).isNotNull();
		assertThat(row.canceledFrom()).isEqualTo("tracking");
		server.verify();
	}

	@Test
	void cancel_pending_detecting_행은_monitoring을_호출하지_않는다() {
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, LocalDate.now(KstTimestamps.KST));
		// server에 어떤 expect()도 등록하지 않는다 — pending 행은 target이 없어 cancel 호출 자체가 없다.

		TrackingItemResponse response = service.cancel(userId, itemId);

		assertThat(response.status()).isEqualTo("not_uploaded");
		MonitoringItemRow row = itemRepository.findByIdAndUser(itemId, userId).orElseThrow();
		assertThat(row.canceledAt()).isNotNull();
		assertThat(row.canceledFrom()).isEqualTo("detecting");
		server.verify();
	}

	@Test
	void cancel하면_매달린_pending_entry가_canceled로_정산되고_registration이_완료된다() {
		// 트랙 LL 회귀 — 예전엔 cancel()이 entry를 건드리지 않아 pending에 영구히 갇히고
		// registration.completed_at도 영영 안 채워졌다(운영 실측 registration id=2, 11시간+).
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, LocalDate.now(KstTimestamps.KST));
		long regId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(regId, 1, "glowdeep", "account", "pending", null, null, null, itemId);
		// server에 어떤 expect()도 등록하지 않는다 — pending(detecting) 행은 monitoring cancel 호출이 없다.

		service.cancel(userId, itemId);

		RegistrationRow row = registrationRepository.findById(regId).orElseThrow();
		assertThat(row.entries().get(0).result()).isEqualTo("canceled");
		assertThat(row.entries().get(0).reasonCode()).isEqualTo("canceled");
		assertThat(row.completedAt()).isNotNull();
		server.verify();
	}

	@Test
	void cancel_이미_success로_정산된_entry는_canceled로_되돌리지_않는다() {
		// 실행기 스레드와 취소 요청이 같은 entry를 동시에 만지는 경합 방어(설계 §4-2) —
		// settleCanceledByItem의 result='pending' 조건이 이미 정산된 entry를 지켜야 한다.
		long targetId = seedTarget("glowdeep", "TRACKING", "SHORT1");
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, LocalDate.now(KstTimestamps.KST));
		itemRepository.confirmTarget(itemId, targetId);
		long regId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(regId, 1, "glowdeep", "account", "success", null, null, null, itemId);

		server.expect(requestTo(BASE + "/api/targets/" + targetId))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withSuccess("{ \"targetId\": " + targetId + ", \"status\": \"CANCELED\" }",
						MediaType.APPLICATION_JSON));

		service.cancel(userId, itemId);

		RegistrationRow row = registrationRepository.findById(regId).orElseThrow();
		assertThat(row.entries().get(0).result()).isEqualTo("success");
		server.verify();
	}

	// ── 리뷰 픽스(2026-07-30) 회귀: trackingDays(유효) + campaign(무효) 조합에서 원격 extend가
	// campaign 검증보다 먼저 나가면 monitoring은 연장되고 로컬은 롤백되는 불일치가 결정론적으로
	// 재현됐다. 아래 두 테스트는 server에 어떤 expect()도 등록하지 않는다 — extend가 먼저 불리면
	// MockRestServiceServer가 "예상 못한 요청"으로 즉시 실패시켜 순서 리그레션을 잡아낸다.

	@Test
	void PATCH_유효한_trackingDays와_존재하지_않는_campaignId_조합은_extend_호출_없이_404이고_app이_그대로다() {
		long targetId = seedTarget("glowdeep", "TRACKING", "SHORT1");
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST);
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, registeredOn);
		itemRepository.confirmTarget(itemId, targetId);

		assertThatThrownBy(() -> service.patch(userId, itemId, Map.of("trackingDays", 30, "campaignId", "999999")))
				.isInstanceOfSatisfying(V1ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

		assertThat(itemRepository.findByIdAndUser(itemId, userId).orElseThrow().trackingDays()).isEqualTo(14);
		server.verify();
	}

	@Test
	void PATCH_유효한_trackingDays와_규칙_위반_campaignName_조합은_extend_호출_없이_400이고_app이_그대로다() {
		long targetId = seedTarget("glowdeep", "TRACKING", "SHORT1");
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST);
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, registeredOn);
		itemRepository.confirmTarget(itemId, targetId);

		assertThatThrownBy(() -> service.patch(userId, itemId, Map.of("trackingDays", 30, "campaignName", "이름/포함")))
				.isInstanceOfSatisfying(V1ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

		assertThat(itemRepository.findByIdAndUser(itemId, userId).orElseThrow().trackingDays()).isEqualTo(14);
		server.verify();
	}
}
