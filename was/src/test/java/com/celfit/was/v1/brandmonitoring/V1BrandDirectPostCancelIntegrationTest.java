package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandPostRegistrationRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.MonitoringCommandClient;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 취소(설계 §2-4)의 "등록→취소→재등록" 왕복 통합 검증 — 실 DB(app 스키마) 위에서 서비스·실행기
 * 조합 전체를 태운다(mock 리포지토리로는 브랜드 풀 재조회가 실제로 재등록을 풀어 주는지 증명할 수
 * 없다). monitoring 호출({@link MonitoringCommandClient})만 mock이다 — was는 그 결과로
 * brand_tagged_post를 직접 쓰지 않으므로(정본은 monitoring), mock의 부수효과로 monitoring이 했을
 * DB 반영(direct_registered_at)을 재현한다.
 *
 * <p>실행기 풀은 {@link SyncTaskExecutor}로 고정한다 — {@code afterCommit} 콜백이 호출 스레드에서
 * 동기로 돌아, register() 호출이 끝난 시점에는 이미 entry가 정산돼 있다(등록 응답 자체는 실행 전
 * pending 스냅샷이라 {@link V1BrandDirectPostService#get}으로 재조회해 확인한다).
 */
class V1BrandDirectPostCancelIntegrationTest extends IntegrationTest {

	private static final String POST_URL = "https://www.instagram.com/reel/DEF/";

	@Autowired
	BrandLinkRepository linkRepository;
	@Autowired
	BrandDirectPostRepository directPostRepository;
	@Autowired
	BrandPostRegistrationRepository registrationRepository;
	@Autowired
	BrandPostCampaignRepository postCampaignRepository;
	@Autowired
	CampaignRepository campaignRepository;
	@Autowired
	DataSource dataSource;
	@Autowired
	JdbcClient jdbcClient;

	JdbcClient monitoringJdbc;
	V1BrandDirectPostService service;
	MonitoringCommandClient commandClient;
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

		BrandReadRepository brandReadRepository = new BrandReadRepository(monitoringJdbc);
		commandClient = mock(MonitoringCommandClient.class);
		BrandDirectRegistrationExecutor executor = new BrandDirectRegistrationExecutor(commandClient,
				registrationRepository, directPostRepository, postCampaignRepository, brandReadRepository,
				new SyncTaskExecutor());
		service = new V1BrandDirectPostService(linkRepository, brandReadRepository, directPostRepository,
				registrationRepository, campaignRepository, postCampaignRepository, commandClient, executor,
				Clock.systemDefaultZone());

		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "brand-direct-cancel-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		brandId = monitoringJdbc.sql("""
				INSERT INTO brand_account (username, ig_user_id, status)
				VALUES ('lizda_official', '17841400000000000', 'ACTIVE')
				RETURNING id
				""").query(Long.class).single();
		linkRepository.insertLink(userId, brandId, "lizda_official", BrandAccountType.OWN, 12);
	}

	/**
	 * commandClient.registerDirectPost의 성공 응답 + 부수효과(monitoring이 real HTTP로 했을
	 * brand_tagged_post upsert)를 함께 흉내낸다 — mock이라 실제 monitoring 서버가 그 행을 만들지 않는다.
	 */
	private void stubSuccessfulRegister(String shortCode) {
		willAnswer(invocation -> {
			monitoringJdbc.sql("""
					INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at,
					                                tag_detected_at, direct_registered_at)
					VALUES (:brandId, :shortCode, 'creator', now(), NULL, now())
					ON CONFLICT (brand_id, short_code) DO UPDATE SET direct_registered_at = EXCLUDED.direct_registered_at
					""")
					.param("brandId", brandId).param("shortCode", shortCode).update();
			return new MonitoringCommandClient.DirectPostResult(shortCode, "creator",
					Instant.parse("2026-08-01T00:00:00Z"), "REELS");
		}).given(commandClient).registerDirectPost(brandId, shortCode, null, false);
	}

	/** commandClient.deleteDirectPost의 부수효과 — monitoring이 했을 direct_registered_at 해제(순수 direct라 행 삭제)를 흉내낸다. */
	private void stubSuccessfulCancel(String shortCode) {
		willAnswer(invocation -> {
			monitoringJdbc.sql("DELETE FROM brand_tagged_post WHERE brand_id = :brandId AND short_code = :shortCode")
					.param("brandId", brandId).param("shortCode", shortCode).update();
			return null;
		}).given(commandClient).deleteDirectPost(brandId, shortCode);
	}

	@Test
	void 등록_취소_재등록_왕복은_새_direct_등록으로_성립한다() {
		stubSuccessfulRegister("DEF");

		BrandDirectRegistrationResponse registered = service.register(userId, brandId, List.of(POST_URL), 30, null);
		BrandDirectRegistrationResponse settled = service.get(userId, registered.registrationId());
		assertThat(settled.entries().get(0).result()).isEqualTo("success");
		assertThat(directPostRepository.findByUserAndShortCode(userId, "DEF")).isPresent();

		stubSuccessfulCancel("DEF");
		service.cancel(userId, "DEF");

		assertThat(directPostRepository.findByUserAndShortCode(userId, "DEF")).isEmpty();

		// 재등록: brand_tagged_post 행이 취소로 지워졌으니 브랜드 풀 중복 판정에 걸리지 않는다.
		BrandDirectRegistrationResponse reregistered = service.register(userId, brandId, List.of(POST_URL), 30, null);
		BrandDirectRegistrationResponse resettled = service.get(userId, reregistered.registrationId());

		assertThat(resettled.entries().get(0).result()).isEqualTo("success");
		assertThat(directPostRepository.findByUserAndShortCode(userId, "DEF")).isPresent();
	}

	@Test
	void 취소는_hashtag_posts_발견_목록에_영향이_없다() {
		// 확인 1(작업 1 주의사항) — 승격 후에도 발견 목록에 남는 현행 동작이 취소로도 깨지지 않아야 한다.
		monitoringJdbc.sql("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username, taken_at,
				                                verdict, verdict_source)
				VALUES (:brandId, 'DEF', '#브랜드명', 'someone_else', now(), 'RELEVANT', 'LLM')
				""")
				.param("brandId", brandId).update();

		stubSuccessfulRegister("DEF");
		service.register(userId, brandId, List.of(POST_URL), 30, null);

		stubSuccessfulCancel("DEF");
		service.cancel(userId, "DEF");

		Long remaining = monitoringJdbc.sql("SELECT count(*) FROM brand_hashtag_post WHERE brand_id = :brandId")
				.param("brandId", brandId).query(Long.class).single();
		assertThat(remaining).isEqualTo(1L);
	}
}
