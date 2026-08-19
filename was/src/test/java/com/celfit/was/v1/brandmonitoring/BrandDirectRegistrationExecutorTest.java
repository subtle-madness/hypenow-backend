package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandPostRegistrationEntryRow;
import com.celfit.was.monitoring.BrandPostRegistrationRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.MonitoringCommandClient;
import java.sql.Connection;
import java.time.Clock;
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
	BrandLinkRepository linkRepository;
	@Autowired
	CampaignRepository campaignRepository;
	@Autowired
	DataSource dataSource;
	@Autowired
	JdbcClient jdbcClient;

	JdbcClient monitoringJdbc;
	BrandReadRepository brandReadRepository;
	MockRestServiceServer server;
	MonitoringCommandClient commandClient;
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
		commandClient = new MonitoringCommandClient(builder.build());
		TaskExecutor immediate = Runnable::run;
		executor = new BrandDirectRegistrationExecutor(commandClient, registrationRepository, directPostRepository,
				postCampaignRepository, immediate);

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

	/**
	 * 접수와 처리 사이의 경합(동시 등록 등) — 등록자 원장 재검사가 이를 잡아야 한다(공동 등록 허용,
	 * 08-19 개정). brand_tagged_post의 direct_registered_at이 아니라 <b>이 유저의</b>
	 * app.brand_direct_posts 행 존재로 판정한다 — 브랜드 단위 재검사였다면 다른 유저의 등록도
	 * duplicate로 오판해 공동 등록이 막혔을 것이다.
	 */
	@Test
	void 처리_직전에_이_유저가_이미_등록했으면_duplicate로_정산되고_monitoring을_다시_부르지_않는다() {
		directPostRepository.upsertDirect(userId, brandId, "RACE1");
		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/RACE1/", "RACE1",
				"pending", null, null);

		executor.submit(registrationId);

		BrandPostRegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("duplicate");
		assertThat(entry.reasonCode()).isEqualTo("duplicate");
		server.verify();   // /api/brands/{id}/direct-posts로 어떤 요청도 안 갔다(기대 0건)
	}

	/**
	 * 공동 등록 허용(요구사항, 08-19) — A가 이미 direct 등록한(brand_tagged_post.direct_registered_at
	 * 있음) shortcode를 B가 등록하면, 등록자 원장 재검사는 B 본인 기준이라(다른 유저의 등록은
	 * duplicate가 아니다) monitoring 호출까지 정상 진행돼 B의 원장에도 행이 생긴다. monitoring의
	 * registerDirectPost는 이미 direct_registered_at이 있는 행에 멱등 200을 돌려주므로(재수집 없음)
	 * 여기서도 같은 응답 셰이프로 스텁한다.
	 */
	@Test
	void 다른_유저가_이미_등록한_shortcode도_공동_등록으로_성공한다() {
		long otherUserId = jdbcClient
				.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "brand-direct-exec-other-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		directPostRepository.upsertDirect(otherUserId, brandId, "COREG1");   // A(otherUserId)가 이미 등록

		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/COREG1/", "COREG1",
				"pending", null, null);

		server.expect(requestTo(BASE + "/api/brands/" + brandId + "/direct-posts"))
				.andRespond(withSuccess("""
						{ "shortCode": "COREG1", "authorUsername": "creator", "takenAt": "2026-08-01T00:00:00Z",
						  "contentType": "REELS" }
						""", MediaType.APPLICATION_JSON));

		executor.submit(registrationId);

		BrandPostRegistrationEntryRow entry = onlyEntry(registrationId);
		assertThat(entry.result()).isEqualTo("success");
		assertThat(directPostRepository.findByUserAndShortCode(userId, "COREG1")).isPresent();
		assertThat(directPostRepository.findByUserAndShortCode(otherUserId, "COREG1")).isPresent();
		server.verify();
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

	// ---------- 취소-복구 경합 회귀(2026-08-18 스테이징 실측) ----------

	/**
	 * 결함 1 회귀 — 등록 응답 유실로 pending에 머문 entry가 있는 상태에서 게시물을 취소하면, 그
	 * entry가 success로 정산돼 5분 뒤 stale 복구가 재등록 콜을 보내지 않아야 한다.
	 *
	 * <p>시나리오 재현: monitoring은 이미 등록을 완료했다(brand_tagged_post.direct_registered_at
	 * 채워짐)는데 was entry는 응답 유실로 pending 잔존 → 사용자가 취소(cancel) → 취소는 monitoring의
	 * direct 표식도 지운다(테스트에선 MockRestServiceServer가 실제로 DB를 건드리지 않으므로, 취소
	 * 처리 후의 monitoring 실측 상태를 수동으로 반영해 재현한다) → 5분 stale 경과 후 복구 크론 실행.
	 * 수정 전에는 이 entry가 여전히 pending이라 복구가 재등록 POST를 보내는데, 그 요청은 테스트가
	 * 기대하지 않은 것이라 MockRestServiceServer가 AssertionError를 던져 테스트가 실패한다(깨뜨려
	 * 확인). 수정 후에는 entry가 이미 success로 정산돼 복구 대상 자체에서 빠져 재등록 콜이 없다.
	 */
	@Test
	void 취소하면_pending_entry가_success로_정산되고_복구가_재등록하지_않는다() {
		monitoringJdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at,
				                                tag_detected_at, direct_registered_at)
				VALUES (:brandId, 'RACE2', 'creator', now(), NULL, now())
				""")
				.param("brandId", brandId).update();

		long registrationId = registrationRepository.insert(userId, brandId, null).id();
		registrationRepository.insertEntry(registrationId, 0, "https://www.instagram.com/reel/RACE2/", "RACE2",
				"pending", null, null);
		linkRepository.insertLink(userId, brandId, "lizda_official", "own", 12);

		server.expect(requestTo(BASE + "/api/brands/" + brandId + "/direct-posts/RACE2"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		V1BrandDirectPostService service = new V1BrandDirectPostService(linkRepository, brandReadRepository,
				directPostRepository, registrationRepository, campaignRepository, postCampaignRepository,
				commandClient, executor, Clock.systemUTC());
		service.cancel(userId, "RACE2");

		BrandPostRegistrationEntryRow settledEntry = onlyEntry(registrationId);
		assertThat(settledEntry.result()).isEqualTo("success");
		assertThat(settledEntry.settledAt()).isNotNull();
		assertThat(registrationRepository.findById(registrationId).orElseThrow().completedAt()).isNotNull();

		// 취소가 monitoring 실서버에 반영했을 direct 표식 해제를 테스트 DB에도 반영 — 취소 후
		// 복구가 재검사하면 더 이상 duplicate로 막아주지 않는 상태를 정확히 재현한다.
		monitoringJdbc.sql("""
				UPDATE brand_tagged_post SET direct_registered_at = NULL
				WHERE brand_id = :brandId AND short_code = 'RACE2'
				""")
				.param("brandId", brandId).update();
		jdbcClient.sql(
				"UPDATE app.brand_post_registrations SET requested_at = now() - interval '10 minutes' WHERE id = :id")
				.param("id", registrationId)
				.update();

		executor.recoverStalePending();

		// 취소 시점 정산 덕에 registrationId가 애초에 복구 대상(findRegistrationIdsWithPendingOlderThan)에
		// 안 잡힌다 — /direct-posts로 어떤 재등록 콜도 안 갔다(기대는 DELETE 1건뿐).
		server.verify();
	}
}
