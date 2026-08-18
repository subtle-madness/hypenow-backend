package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandPostRegistrationEntryRow;
import com.celfit.was.monitoring.BrandPostRegistrationRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.MonitoringCommandClient;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * BrandDirectRegistrationExecutor — 브랜드 direct 등록 접수(§T9) 이후 pending entry 처리를
 * 검증한다(2026-08-18 direct 통합 §T8). app 스키마는 IntegrationTest 실 컨테이너(리포지토리 왕복까지
 * 확인), monitoring 브랜드 풀은 같은 컨테이너에 {@code monitoring-brand-schema.sql}을 얹어 재현하고,
 * monitoring 서버 호출은 MockRestServiceServer로 대역한다. 실행기는 Spring 컨텍스트의
 * monitoring.enabled 조건부 배선과 무관하게 테스트에서 직접 new — 스레드풀도 결정론을 위해
 * 즉시 실행(Runnable::run)으로 대체한다.
 */
class BrandDirectRegistrationExecutorTest extends IntegrationTest {

	static final String BASE = "http://monitoring:8083";

	@Autowired
	BrandPostRegistrationRepository registrationRepository;
	@Autowired
	BrandDirectPostRepository directPostRepository;
	@Autowired
	BrandPostCampaignRepository postCampaignRepository;
	@Autowired
	DataSource dataSource;
	@Autowired
	JdbcClient jdbcClient;

	JdbcClient monitoringJdbc;
	BrandReadRepository brandReadRepository;
	MockRestServiceServer server;
	BrandDirectRegistrationExecutor executor;
	long userId;
	long brandId;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("""
				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
				         brand_post_comment, author_profile, brand_hashtag_post, brand_hashtag_exclusion
				         RESTART IDENTITY CASCADE
				""")
				.update();
		brandReadRepository = new BrandReadRepository(monitoringJdbc);

		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		MonitoringCommandClient client = new MonitoringCommandClient(builder.build());
		TaskExecutor immediate = Runnable::run;
		executor = new BrandDirectRegistrationExecutor(client, registrationRepository, directPostRepository,
				postCampaignRepository, brandReadRepository, immediate);

		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "brand-direct-exec-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		brandId = monitoringJdbc.sql("""
				INSERT INTO brand_account (username, ig_user_id, status)
				VALUES ('lizda_official', '17841400000000000', 'ACTIVE')
				RETURNING id
				""").query(Long.class).single();
	}

	private BrandPostRegistrationEntryRow onlyEntry(long registrationId) {
		return registrationRepository.findById(registrationId).orElseThrow().entries().get(0);
	}

	@Test
	void direct_등록_성공하면_원장이_생기고_entry가_success다() {
		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/ABC123/", "ABC123",
				"pending", null, null);

		server.expect(requestTo(BASE + "/api/brands/" + brandId + "/direct-posts"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.shortCode").value("ABC123"))
				.andRespond(withSuccess("""
						{ "shortCode": "ABC123", "authorUsername": "creator", "takenAt": "2026-08-01T00:00:00Z",
						  "contentType": "REELS" }
						""", MediaType.APPLICATION_JSON));

		executor.submit(registrationId);

		BrandPostRegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("success");
		assertThat(directPostRepository.findByUserAndShortCode(userId, "ABC123")).isPresent();
		assertThat(registrationRepository.findById(registrationId).orElseThrow().completedAt()).isNotNull();
		server.verify();
	}

	@Test
	void 등록_API_404면_entry가_failed로_정산된다() {
		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/NOPE/", "NOPE",
				"pending", null, null);

		server.expect(requestTo(BASE + "/api/brands/" + brandId + "/direct-posts"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"POST_NOT_FOUND\", \"message\": \"게시물을 찾을 수 없습니다.\" }"));

		executor.submit(registrationId);

		BrandPostRegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("failed");
		assertThat(entry.reasonCode()).isEqualTo("not_found");
		assertThat(directPostRepository.findByUserAndShortCode(userId, "NOPE")).isEmpty();
		server.verify();
	}

	@Test
	void 전송_실패_503이면_pending_유지된다() {
		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/RETRY1/", "RETRY1",
				"pending", null, null);

		server.expect(requestTo(BASE + "/api/brands/" + brandId + "/direct-posts"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY));

		executor.submit(registrationId);

		BrandPostRegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("pending");
		assertThat(registrationRepository.findById(registrationId).orElseThrow().completedAt()).isNull();
		server.verify();
	}

	@Test
	void recoverStalePending은_5분_넘은_pending_등록을_재처리한다() {
		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/STALE1/", "STALE1",
				"pending", null, null);
		jdbcClient.sql(
				"UPDATE app.brand_post_registrations SET requested_at = now() - interval '10 minutes' WHERE id = :id")
				.param("id", registrationId)
				.update();

		server.expect(requestTo(BASE + "/api/brands/" + brandId + "/direct-posts"))
				.andRespond(withSuccess("""
						{ "shortCode": "STALE1", "authorUsername": "creator", "takenAt": "2026-08-01T00:00:00Z",
						  "contentType": "FEED" }
						""", MediaType.APPLICATION_JSON));

		executor.recoverStalePending();

		BrandPostRegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("success");
		assertThat(registrationRepository.findById(registrationId).orElseThrow().completedAt()).isNotNull();
		server.verify();
	}

	@Test
	void share_해소_성공하면_shortCode가_확정되고_등록도_성공한다() {
		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/share/reel/AbCdEfG/", null,
				"pending", null, null);

		server.expect(requestTo(BASE + "/api/share/resolve"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.url").value("https://www.instagram.com/share/reel/AbCdEfG/"))
				.andRespond(withSuccess("""
						{ "shortCode": "SHARE1", "username": "rarebeauty", "contentType": "REELS" }
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/brands/" + brandId + "/direct-posts"))
				.andExpect(jsonPath("$.shortCode").value("SHARE1"))
				.andRespond(withSuccess("""
						{ "shortCode": "SHARE1", "authorUsername": "rarebeauty", "takenAt": "2026-08-01T00:00:00Z",
						  "contentType": "REELS" }
						""", MediaType.APPLICATION_JSON));

		executor.submit(registrationId);

		BrandPostRegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("success");
		assertThat(entry.shortCode()).isEqualTo("SHARE1");
		assertThat(directPostRepository.findByUserAndShortCode(userId, "SHARE1")).isPresent();
		server.verify();
	}

	@Test
	void share_해소_실패면_entry가_failed로_기록된다() {
		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/share/reel/bad/", null,
				"pending", null, null);

		server.expect(requestTo(BASE + "/api/share/resolve"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"SHARE_LINK_UNRESOLVED\", \"message\": \"해소 불가\" }"));

		executor.submit(registrationId);

		BrandPostRegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("failed");
		assertThat(entry.reasonCode()).isEqualTo("share_link_unresolved");
		server.verify();
	}

	@Test
	void 처리_직전에_이미_direct_등록됐으면_duplicate로_정산되고_monitoring을_다시_부르지_않는다() {
		// 접수와 처리 사이의 경합(동시 등록 등) — 브랜드 풀 재검사가 이를 잡아야 한다.
		monitoringJdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at,
				                                tag_detected_at, direct_registered_at)
				VALUES (:brandId, 'RACE1', 'creator', now(), NULL, now())
				""")
				.param("brandId", brandId).update();
		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/RACE1/", "RACE1",
				"pending", null, null);

		executor.submit(registrationId);

		BrandPostRegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("duplicate");
		assertThat(entry.reasonCode()).isEqualTo("duplicate");
		server.verify();   // /api/brands/{id}/direct-posts로 어떤 요청도 안 갔다(기대 0건)
	}

	@Test
	void campaignId가_있으면_등록_성공_시_브랜드_풀_캠페인_링크가_생긴다() {
		long campaignId = jdbcClient
				.sql("INSERT INTO app.monitoring_campaigns (user_id, name) VALUES (:userId, :name) RETURNING id")
				.param("userId", userId).param("name", "여름 캠페인").query(Long.class).single();
		long registrationId = registrationRepository.insert(userId, brandId, campaignId).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/CMP1/", "CMP1",
				"pending", null, null);

		server.expect(requestTo(BASE + "/api/brands/" + brandId + "/direct-posts"))
				.andRespond(withSuccess("""
						{ "shortCode": "CMP1", "authorUsername": "creator", "takenAt": "2026-08-01T00:00:00Z",
						  "contentType": "REELS" }
						""", MediaType.APPLICATION_JSON));

		executor.submit(registrationId);

		Long linked = jdbcClient.sql("""
				SELECT count(*) FROM app.brand_post_campaigns
				WHERE brand_id = :brandId AND short_code = 'CMP1' AND campaign_id = :campaignId
				""")
				.param("brandId", brandId).param("campaignId", campaignId).query(Long.class).single();
		assertThat(linked).isEqualTo(1L);
	}

	@Test
	void 전_entry_종결되면_registration이_완료로_마킹된다() {
		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/OK1/", "OK1", "pending",
				null, null);
		registrationRepository.insertEntry(registrationId, 1, "https://www.instagram.com/reel/FAIL1/", "FAIL1",
				"pending", null, null);

		server.expect(requestTo(BASE + "/api/brands/" + brandId + "/direct-posts"))
				.andRespond(withSuccess("""
						{ "shortCode": "OK1", "authorUsername": "creator", "takenAt": "2026-08-01T00:00:00Z",
						  "contentType": "REELS" }
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/brands/" + brandId + "/direct-posts"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"PRIVATE_ACCOUNT\", \"message\": \"비공개\" }"));

		executor.submit(registrationId);

		assertThat(registrationRepository.findById(registrationId).orElseThrow().completedAt()).isNotNull();
		server.verify();
	}

	@Test
	void settleStaleRegistrationEntries는_임계_초과_pending_entry를_failed로_확정하고_완료_마킹한다() {
		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/STALEENTRY1/",
				"STALEENTRY1", "pending", null, null);
		jdbcClient.sql(
				"UPDATE app.brand_post_registrations SET requested_at = now() - interval '25 hours' WHERE id = :id")
				.param("id", registrationId)
				.update();

		executor.settleStaleRegistrationEntries(Duration.ofHours(24));

		BrandPostRegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("failed");
		assertThat(entry.reasonCode()).isEqualTo("internal_error");
		assertThat(registrationRepository.findById(registrationId).orElseThrow().completedAt()).isNotNull();
	}
}
