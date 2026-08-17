package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CancelResult;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.v1.monitoring.RegistrationExecutor;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.V1CampaignService;
import com.celfit.was.v1.monitoring.V1MonitoringItemUpdateService;
import com.celfit.was.v1.monitoring.V1MonitoringRegistrationService;
import java.sql.Connection;
import java.util.List;
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
 * 취소(2026-08-17 FE 요청)의 "등록→취소→재등록" 왕복 통합 검증 — 실 DB 위에서 서비스 조합 전체를
 * 태운다(mock 리포지토리로는 canceled_at 필터링이 실제로 재등록을 풀어 주는지 증명할 수 없다).
 * V1MonitoringRegistrationDuplicateExclusionTest와 같은 관용구 — monitoring-schema.sql(target)·
 * monitoring-brand-schema.sql(brand_account)을 같은 컨테이너의 public 스키마에 적용하고 서비스를
 * 직접 조립해 실 target 조회·실 canceled_at 필터를 태운다.
 */
class V1BrandDirectPostCancelIntegrationTest extends IntegrationTest {

	private static final String POST_URL = "https://www.instagram.com/reel/DEF/";

	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	RegistrationRepository registrationRepository;
	@Autowired
	CampaignRepository campaignRepository;
	@Autowired
	BrandLinkRepository linkRepository;
	@Autowired
	BrandDirectPostRepository directPostRepository;
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
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot RESTART IDENTITY")
				.update();
		monitoringJdbc.sql("""
				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
				         brand_post_comment, author_profile, brand_hashtag_post, brand_hashtag_exclusion
				         RESTART IDENTITY CASCADE
				""")
				.update();

		BrandReadRepository brandReadRepository = new BrandReadRepository(monitoringJdbc);
		MonitoringReadRepository readRepository = new MonitoringReadRepository(monitoringJdbc);
		V1CampaignService campaignService = new V1CampaignService(campaignRepository);
		TrackingItemAssembler assembler = new TrackingItemAssembler(itemRepository, campaignRepository,
				Optional.of(readRepository), new ObjectMapper());
		V1MonitoringRegistrationService legacyRegistration = new V1MonitoringRegistrationService(itemRepository,
				registrationRepository, campaignRepository, campaignService, mock(RegistrationExecutor.class),
				new ObjectMapper(), Optional.of(readRepository));
		commandClient = mock(MonitoringCommandClient.class);
		V1MonitoringItemUpdateService itemUpdateService = new V1MonitoringItemUpdateService(itemRepository,
				campaignRepository, campaignService, Optional.of(readRepository), Optional.of(commandClient),
				assembler, registrationRepository);
		service = new V1BrandDirectPostService(linkRepository, brandReadRepository, directPostRepository,
				itemRepository, readRepository, legacyRegistration, registrationRepository, itemUpdateService);

		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "brand-direct-cancel-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		brandId = monitoringJdbc.sql("""
				INSERT INTO brand_account (username, ig_user_id, status)
				VALUES ('lizda_official', '17841400000000000', 'ACTIVE')
				RETURNING id
				""").query(Long.class).single();
		linkRepository.insertLink(userId, brandId, "lizda_official", BrandAccountType.OWN);
	}

	/** 등록 직후 collecting(target 미확정) 상태의 아이템을 TRACKING 상태로 끌어올린다(취소 가능 상태). */
	private void confirmTracking(long itemId, String shortCode) {
		long targetId = monitoringJdbc.sql("""
				INSERT INTO target (type, username, short_code, status, tracked_short_code, registration_key,
				                    expires_at)
				VALUES ('POST', :shortCode, :shortCode, 'TRACKING', :shortCode, gen_random_uuid()::text,
				        now() + interval '30 days')
				RETURNING id
				""")
				.param("shortCode", shortCode)
				.query(Long.class).single();
		itemRepository.confirmTarget(itemId, targetId);
		given(commandClient.cancel(targetId)).willReturn(new CancelResult(targetId, "CANCELED"));
	}

	@Test
	void 등록_취소_재등록_왕복은_새_추적_행을_만든다() {
		BrandDirectRegistrationResponse registered = service.register(userId, brandId, List.of(POST_URL), 30, null);
		assertThat(registered.entries().get(0).result()).isEqualTo("pending");
		long firstItemId = Long.parseLong(registered.entries().get(0).monitoringItemId());
		confirmTracking(firstItemId, "DEF");

		// 취소 전: 매핑이 살아 있고 레거시 아이템은 취소되지 않았다.
		assertThat(directPostRepository.findByUserAndShortCode(userId, "DEF")).isPresent();
		MonitoringItemRow before = itemRepository.findByIdAndUser(firstItemId, userId).orElseThrow();
		assertThat(before.canceledAt()).isNull();

		service.cancel(userId, "DEF");

		// 취소 후: 레거시 아이템이 종결되고 매핑은 삭제된다.
		MonitoringItemRow after = itemRepository.findByIdAndUser(firstItemId, userId).orElseThrow();
		assertThat(after.canceledAt()).isNotNull();
		assertThat(directPostRepository.findByUserAndShortCode(userId, "DEF")).isEmpty();

		// 재등록: canceled_at IS NOT NULL이라 레거시 duplicate 판정에서 제외되고, 새 pending 행이 생긴다.
		BrandDirectRegistrationResponse reregistered = service.register(userId, brandId, List.of(POST_URL), 30, null);

		assertThat(reregistered.entries().get(0).result()).isEqualTo("pending");
		long secondItemId = Long.parseLong(reregistered.entries().get(0).monitoringItemId());
		assertThat(secondItemId).isNotEqualTo(firstItemId);
		assertThat(directPostRepository.findByUserAndShortCode(userId, "DEF"))
				.hasValueSatisfying(row -> assertThat(row.monitoringItemId()).isEqualTo(secondItemId));
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

		BrandDirectRegistrationResponse registered = service.register(userId, brandId, List.of(POST_URL), 30, null);
		long itemId = Long.parseLong(registered.entries().get(0).monitoringItemId());
		confirmTracking(itemId, "DEF");

		service.cancel(userId, "DEF");

		Long remaining = monitoringJdbc.sql("SELECT count(*) FROM brand_hashtag_post WHERE brand_id = :brandId")
				.param("brandId", brandId).query(Long.class).single();
		assertThat(remaining).isEqualTo(1L);
	}
}
