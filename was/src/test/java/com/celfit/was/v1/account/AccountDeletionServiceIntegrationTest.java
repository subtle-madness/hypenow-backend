package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.celfit.was.IntegrationTest;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringItemRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 탈퇴 시 모니터링 해지 루프(프론트 계약 4절 31번, 갭 문서 B-2) 검증. app DB는 IntegrationTest 실
 * 컨테이너(CASCADE까지 확인), monitoring 서버는 MockRestServiceServer로 대역한다
 * (V1MonitoringItemUpdateIntegrationTest와 동일 관용구). 여기서는 target 조회 표면이 필요 없어
 * monitoring-schema.sql 픽스처는 붙이지 않는다 — cancel은 target_id 존재만 전제한다.
 */
class AccountDeletionServiceIntegrationTest extends IntegrationTest {

	static final String BASE = "http://monitoring:8083";

	@Autowired
	UserRepository userRepository;
	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	JdbcClient jdbcClient;

	MockRestServiceServer server;
	long userId;

	@BeforeEach
	void setUp() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "withdraw-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	/** 브랜드 정리 훅은 여기 범위 밖 — monitoring.enabled=false와 같은 상태(빈 부재)로 고정한다. */
	private AccountDeletionService serviceWith(Optional<MonitoringCommandClient> commandClient) {
		return new AccountDeletionService(userRepository, itemRepository, commandClient, Optional.empty());
	}

	private long insertConfirmedItem(String handle, long targetId) {
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, handle, null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, LocalDate.now());
		itemRepository.confirmTarget(itemId, targetId);
		return itemId;
	}

	@Test
	void 탈퇴는_확정_target_전부를_해지하고_pending_행은_호출하지_않는다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		MonitoringCommandClient commandClient = new MonitoringCommandClient(builder.build());
		insertConfirmedItem("glowdeep", 101L);
		insertConfirmedItem("skincrew", 202L);
		// pending 행 — target_id NULL, 아래 어떤 expect()도 등록하지 않아 호출되면 즉시 실패한다.
		itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "pendinguser", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, LocalDate.now());

		server.expect(requestTo(BASE + "/api/targets/101"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withSuccess("{ \"targetId\": 101, \"status\": \"CANCELED\" }", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/targets/202"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withSuccess("{ \"targetId\": 202, \"status\": \"CANCELED\" }", MediaType.APPLICATION_JSON));

		serviceWith(Optional.of(commandClient)).deleteAccount(userId);

		server.verify();
		assertThat(userRepository.findById(userId)).isEmpty();
	}

	@Test
	void 해지_1건_실패해도_나머지_해지와_탈퇴는_계속된다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		MonitoringCommandClient commandClient = new MonitoringCommandClient(builder.build());
		insertConfirmedItem("glowdeep", 301L);
		insertConfirmedItem("skincrew", 302L);

		server.expect(requestTo(BASE + "/api/targets/301"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withServerError());
		server.expect(requestTo(BASE + "/api/targets/302"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withSuccess("{ \"targetId\": 302, \"status\": \"CANCELED\" }", MediaType.APPLICATION_JSON));

		serviceWith(Optional.of(commandClient)).deleteAccount(userId);

		server.verify();   // 302 호출까지 도달했음을 함께 증명 — 한 건 실패가 루프를 끊지 않는다
		assertThat(userRepository.findById(userId)).isEmpty();
	}

	@Test
	void monitoring_비활성이면_해지_호출_없이_탈퇴만_정상_진행된다() {
		insertConfirmedItem("glowdeep", 401L);
		// commandClient가 Optional.empty()이므로 monitoringItemRepository 조회조차 스킵 — RestClient가
		// 아예 없으니 호출됐다면 NPE 아닌 컴파일조차 안 됐을 것. 여기서는 정상 완료만 확인한다.

		serviceWith(Optional.empty()).deleteAccount(userId);

		assertThat(userRepository.findById(userId)).isEmpty();
	}

	/**
	 * 조직·엔터프라이즈 entitlement(2026-08-17) 발견 결함 회귀 — app.organization_members.user_id FK에
	 * ON DELETE CASCADE가 없으면 조직 멤버가 탈퇴할 때 users DELETE가 FK 위반으로 실패한다
	 * (V20260817030344__organizations.sql에서 수정). 탈퇴는 정상 완료되고 멤버십 행도 함께 사라져야
	 * 한다. CASCADE로 사라지는 행이라 archive.archived_rows 이관까지 확인해야 아카이브 가드
	 * (ArchiveCascadeReachabilityTest)가 요구하는 "CASCADE 삭제 전 반드시 이관" 계약을 검증한 게 된다
	 * (ArchiveTables.ORGANIZATION_MEMBERS·ACCOUNT_DELETION_ORDER 배선).
	 */
	@Test
	void 조직_멤버인_유저의_탈퇴는_성공하고_멤버십_행은_CASCADE로_사라지되_아카이브에는_남는다() {
		long orgId = jdbcClient.sql("""
				INSERT INTO app.organizations (name, plan) VALUES ('탈퇴 테스트 조직', 'ENTERPRISE') RETURNING id
				""")
				.query(Long.class)
				.single();
		jdbcClient.sql("INSERT INTO app.organization_members (org_id, user_id) VALUES (:orgId, :userId)")
				.param("orgId", orgId)
				.param("userId", userId)
				.update();

		serviceWith(Optional.empty()).deleteAccount(userId);

		assertThat(userRepository.findById(userId)).isEmpty();
		Long remaining = jdbcClient.sql("SELECT count(*) FROM app.organization_members WHERE user_id = :userId")
				.param("userId", userId)
				.query(Long.class)
				.single();
		assertThat(remaining).isZero();

		Long archivedCount = jdbcClient.sql("""
				SELECT count(*) FROM archive.archived_rows
				WHERE table_name = 'app.organization_members' AND user_id = :userId
				  AND archived_reason = 'ACCOUNT_DELETION'
				""")
				.param("userId", userId)
				.query(Long.class)
				.single();
		assertThat(archivedCount).isEqualTo(1L);
	}
}
