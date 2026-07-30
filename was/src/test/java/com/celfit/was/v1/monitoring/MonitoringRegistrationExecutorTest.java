package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * MonitoringRegistrationExecutor — 등록 접수(6.27) 이후 백그라운드 첫 확인을 검증한다.
 * DB는 IntegrationTest 실 컨테이너(리포지토리 왕복까지 확인), monitoring 서버는 MockRestServiceServer로
 * 대역한다. 실행기는 Spring 컨텍스트의 monitoring.enabled 조건부 배선과 무관하게 테스트에서 직접
 * new — 스레드풀도 결정론을 위해 즉시 실행(Runnable::run)으로 대체한다(submit() 반환 시점에
 * 처리가 이미 끝나 있어야 뒤따르는 단언이 안정적).
 */
class MonitoringRegistrationExecutorTest extends IntegrationTest {

	static final String BASE = "http://monitoring:8083";

	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	RegistrationRepository registrationRepository;
	@Autowired
	JdbcClient jdbcClient;

	MockRestServiceServer server;
	MonitoringRegistrationExecutor executor;
	long userId;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		MonitoringCommandClient client = new MonitoringCommandClient(builder.build());
		TaskExecutor immediate = Runnable::run;
		executor = new MonitoringRegistrationExecutor(client, itemRepository, registrationRepository,
				new ObjectMapper(), immediate);

		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-exec-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	private RegistrationEntryRow onlyEntry(long registrationId) {
		return registrationRepository.findById(registrationId).orElseThrow().entries().get(0);
	}

	@Test
	void post_등록_성공하면_confirm되고_entry_success다() {
		UUID key = UUID.randomUUID();
		long itemId = itemRepository.insertPending(userId, "url", key, null, "ABC123",
				"https://www.instagram.com/p/ABC123/", null, 14, LocalDate.of(2026, 7, 30));
		long registrationId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/p/ABC123/", "post",
				"pending", null, null, null, itemId);

		server.expect(requestTo(BASE + "/api/targets"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.userId").value((int) userId))
				.andExpect(jsonPath("$.registrationKey").value(key.toString()))
				.andExpect(jsonPath("$.shortCode").value("ABC123"))
				.andRespond(withSuccess("""
						{ "targetId": 900, "status": "TRACKING" }
						""", MediaType.APPLICATION_JSON));

		executor.submit(registrationId);

		assertThat(itemRepository.findByIdAndUser(itemId, userId).orElseThrow().targetId()).isEqualTo(900L);
		RegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("success");
		assertThat(registrationRepository.findById(registrationId).orElseThrow().completedAt()).isNotNull();
		server.verify();
	}

	@Test
	void account_등록은_or_키워드가_any로_매핑되어_전송된다() {
		String keywordsJson = "{\"and\":[\"글로우딥\"],\"or\":[\"A\",\"B\"],\"exclude\":[\"나눔\"]}";
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep.official",
				null, keywordsJson, 14, LocalDate.of(2026, 7, 30));
		long registrationId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(registrationId, 0, "glowdeep.official", "account", "pending", null, null,
				null, itemId);

		server.expect(requestTo(BASE + "/api/targets"))
				.andExpect(jsonPath("$.userId").value((int) userId))
				.andExpect(jsonPath("$.type").value("ACCOUNT"))
				.andExpect(jsonPath("$.keywordRule.and[0]").value("글로우딥"))
				.andExpect(jsonPath("$.keywordRule.any[0]").value("A"))
				.andExpect(jsonPath("$.keywordRule.any[1]").value("B"))
				.andExpect(jsonPath("$.keywordRule.exclude[0]").value("나눔"))
				.andRespond(withSuccess("{ \"targetId\": 902, \"status\": \"WATCHING\" }", MediaType.APPLICATION_JSON));

		executor.submit(registrationId);

		assertThat(itemRepository.findByIdAndUser(itemId, userId).orElseThrow().targetId()).isEqualTo(902L);
		server.verify();
	}

	@Test
	void 등록_API_실패면_행_삭제되고_코드가_매핑된다() {
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "NOPE",
				"https://www.instagram.com/p/NOPE/", null, 14, LocalDate.of(2026, 7, 30));
		long registrationId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/p/NOPE/", "post", "pending",
				null, null, null, itemId);

		server.expect(requestTo(BASE + "/api/targets"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"SUBJECT_NOT_FOUND\", \"message\": \"없음\" }"));

		executor.submit(registrationId);

		assertThat(itemRepository.findByIdAndUser(itemId, userId)).isEmpty();
		RegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("failed");
		assertThat(entry.reasonCode()).isEqualTo("not_found");
		server.verify();
	}

	@Test
	void 전송_실패면_1회_재시도_후에도_pending_유지된다() {
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "RETRY1",
				"https://www.instagram.com/p/RETRY1/", null, 14, LocalDate.of(2026, 7, 30));
		long registrationId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/p/RETRY1/", "post",
				"pending", null, null, null, itemId);

		server.expect(requestTo(BASE + "/api/targets")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
		server.expect(requestTo(BASE + "/api/targets")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

		executor.submit(registrationId);

		assertThat(itemRepository.findByIdAndUser(itemId, userId)).isPresent();
		RegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("pending");
		assertThat(registrationRepository.findById(registrationId).orElseThrow().completedAt()).isNull();
		server.verify();
	}

	@Test
	void share_해소_성공하면_행이_생성되고_등록도_성공한다() {
		long registrationId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/share/reel/AbCdEfG/",
				"post", "pending", null, null, null, null);

		server.expect(requestTo(BASE + "/api/share/resolve"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.url").value("https://www.instagram.com/share/reel/AbCdEfG/"))
				.andRespond(withSuccess("""
						{ "shortCode": "SHARE1", "username": "rarebeauty", "contentType": "REELS" }
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/targets"))
				.andExpect(jsonPath("$.userId").value((int) userId))
				.andExpect(jsonPath("$.shortCode").value("SHARE1"))
				.andRespond(withSuccess("{ \"targetId\": 901, \"status\": \"TRACKING\" }", MediaType.APPLICATION_JSON));

		executor.submit(registrationId);

		RegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("success");
		assertThat(entry.resolvedUrl()).isEqualTo("https://www.instagram.com/reel/SHARE1/");
		assertThat(entry.itemId()).isNotNull();
		MonitoringItemRow item = itemRepository.findByIdAndUser(entry.itemId(), userId).orElseThrow();
		assertThat(item.targetId()).isEqualTo(901L);
		assertThat(item.trackingDays()).isEqualTo(14);
		server.verify();
	}

	@Test
	void share_해소_실패면_entry가_실패로_기록된다() {
		long registrationId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/share/reel/bad/", "post",
				"pending", null, null, null, null);

		server.expect(requestTo(BASE + "/api/share/resolve"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"SHARE_LINK_UNRESOLVED\", \"message\": \"해소 불가\" }"));

		executor.submit(registrationId);

		RegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("failed");
		assertThat(entry.reasonCode()).isEqualTo("share_link_unresolved");
		assertThat(itemRepository.findByUser(userId)).isEmpty();
		server.verify();
	}

	@Test
	void share_해소_후_shortcode가_기존_행과_겹치면_duplicate다() {
		itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "DUP1",
				"https://www.instagram.com/p/DUP1/", null, 30, LocalDate.of(2026, 7, 30));
		long registrationId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/share/reel/dup/", "post",
				"pending", null, null, null, null);

		server.expect(requestTo(BASE + "/api/share/resolve"))
				.andRespond(withSuccess("""
						{ "shortCode": "DUP1", "username": "acct", "contentType": "FEED" }
						""", MediaType.APPLICATION_JSON));

		executor.submit(registrationId);

		RegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("duplicate");
		assertThat(entry.itemId()).isNull();
		server.verify();
	}

	@Test
	void 전_entry_종결되면_registration이_완료로_마킹된다() {
		long okItemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "OK1",
				"https://www.instagram.com/p/OK1/", null, 14, LocalDate.of(2026, 7, 30));
		long failItemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "FAIL1",
				"https://www.instagram.com/p/FAIL1/", null, 14, LocalDate.of(2026, 7, 30));
		long registrationId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/p/OK1/", "post", "pending",
				null, null, null, okItemId);
		registrationRepository.insertEntry(registrationId, 1, "https://www.instagram.com/p/FAIL1/", "post",
				"pending", null, null, null, failItemId);

		server.expect(requestTo(BASE + "/api/targets"))
				.andRespond(withSuccess("{ \"targetId\": 910, \"status\": \"TRACKING\" }", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/targets"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"PRIVATE_ACCOUNT\", \"message\": \"비공개\" }"));

		executor.submit(registrationId);

		assertThat(registrationRepository.findById(registrationId).orElseThrow().completedAt()).isNotNull();
		server.verify();
	}

	@Test
	void 취소된_행은_백그라운드_등록을_건너뛴다() {
		// 접수(6.27) 커밋 ~ 백그라운드 첫 확인 사이에 6.30 cancel이 먼저 온 경합 — item은
		// markCanceled로 이미 종결됐지만 entry는 여전히 pending인 채 남아 있다(V1MonitoringItemUpdateService
		// 참조). 실행기는 target 생성 없이 건너뛰어야 한다(monitoring에 등록 요청을 보내면 안 됨).
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "CANCELED1",
				"https://www.instagram.com/p/CANCELED1/", null, 14, LocalDate.of(2026, 7, 30));
		long registrationId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/p/CANCELED1/", "post",
				"pending", null, null, null, itemId);
		itemRepository.markCanceled(itemId, "detecting", OffsetDateTime.now());

		executor.submit(registrationId);

		assertThat(itemRepository.findByIdAndUser(itemId, userId).orElseThrow().targetId()).isNull();
		RegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("pending");   // 취소된 행은 건드리지 않고 원상태로 둔다
		server.verify();   // 등록되지 않았어야 하므로 /api/targets로 어떤 요청도 안 갔다(기대 0건)
	}

	@Test
	void recoverStalePending은_오래된_pending_행을_같은_키로_replay한다() {
		UUID key = UUID.randomUUID();
		long itemId = itemRepository.insertPending(userId, "url", key, null, "STALE1",
				"https://www.instagram.com/p/STALE1/", null, 14, LocalDate.of(2026, 7, 30));
		jdbcClient.sql("UPDATE app.monitoring_items SET created_at = now() - interval '10 minutes' WHERE id = :id")
				.param("id", itemId)
				.update();
		long registrationId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/p/STALE1/", "post",
				"pending", null, null, null, itemId);

		// IntegrationTest는 JVM 전체에서 Postgres 컨테이너를 공유한다(싱글턴) — 다른 테스트 클래스가
		// 의도적으로 남겨 둔 오래된 pending 행(예: MonitoringItemRepositoryTest의 findPendingOlderThan
		// 픽스처)도 findPendingOlderThan()이 함께 주워올 수 있어 호출 횟수·바디를 특정하지 않고
		// URL만 느슨하게 매칭한다 — 우리가 확인할 것은 우리 행의 결과뿐이다.
		server.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/api/targets"))
				.andRespond(withSuccess("{ \"targetId\": 920, \"status\": \"TRACKING\" }", MediaType.APPLICATION_JSON));

		executor.recoverStalePending();

		assertThat(itemRepository.findByIdAndUser(itemId, userId).orElseThrow().targetId()).isEqualTo(920L);
		RegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("success");
		assertThat(registrationRepository.findById(registrationId).orElseThrow().completedAt()).isNotNull();
	}
}
