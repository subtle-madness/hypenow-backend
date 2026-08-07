package com.celfit.was.v2.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.v1.monitoring.RegistrationExecutor;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.V1CampaignService;
import com.celfit.was.v1.monitoring.V1MonitoringRegistrationService;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
 * v2 캠페인 콘텐츠의 실 DB 계약을 고정한다. 단위 테스트(mock)가 가려주는 가정 두 개가 대상이다 —
 * 하부가 바뀌면 v2 단위 테스트는 전부 green인 채로 운영이 깨지는 지점:
 * <ol>
 *   <li>제거의 {@link CampaignItemLinker#unlink} — 슬림 경로가 실제로 행의 campaign_id를
 *       비우는가(레거시 patch 위임을 슬림 경로로 교체한 뒤에도 유지돼야 하는 효과)</li>
 *   <li>위임 등록 본문의 {@code campaignId}는 <b>String</b>("42")이다 — 레거시 register가 문자열을
 *       파싱해 새 아이템에 캠페인을 걸어주는가(v2는 이 연결을 믿고 추가 연결을 안 한다)</li>
 * </ol>
 * 배선은 V1MonitoringRegistrationDuplicateExclusionTest와 동일 — monitoring-schema.sql을 같은
 * 컨테이너의 public 스키마에 적용하고 서비스를 직접 생성한다.
 */
class V2CampaignContentIntegrationTest extends IntegrationTest {

	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	RegistrationRepository registrationRepository;
	@Autowired
	CampaignRepository campaignRepository;
	@Autowired
	BrandLinkRepository linkRepository;
	@Autowired
	DataSource dataSource;
	@Autowired
	JdbcClient jdbcClient;

	V2CampaignContentService service;
	V1MonitoringRegistrationService registrationService;
	long userId;
	long campaignId;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
		}
		JdbcClient monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot RESTART IDENTITY")
				.update();

		MonitoringReadRepository readRepository = new MonitoringReadRepository(monitoringJdbc);
		V1CampaignService campaignService = new V1CampaignService(campaignRepository);
		TrackingItemAssembler assembler = new TrackingItemAssembler(itemRepository, campaignRepository,
				Optional.of(readRepository), new ObjectMapper());
		registrationService = new V1MonitoringRegistrationService(itemRepository,
				registrationRepository, campaignRepository, campaignService, mock(RegistrationExecutor.class),
				new ObjectMapper(), Optional.of(readRepository));
		// 브랜드 표면은 이 계약 검증과 무관 — tagged 경로는 비활성(Optional.empty)으로 둔다.
		service = new V2CampaignContentService(campaignRepository, assembler,
				new CampaignItemLinker(campaignRepository, itemRepository),
				registrationService, linkRepository, itemRepository, Optional.empty(), Optional.empty());

		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "v2-campaign-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		campaignId = campaignRepository.insert(userId, "여름 캠페인", null, null, null, null, null, null).id();
	}

	@Test
	void 제거는_슬림_경로를_통해_실제로_campaign_id를_비운다() {
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), campaignId, "ABC123",
				"https://www.instagram.com/p/ABC123/", null, 30, LocalDate.now());

		service.remove(userId, campaignId, "ABC123");

		assertThat(itemRepository.findByIdAndUser(itemId, userId).orElseThrow().campaignId()).isNull();
	}

	@Test
	void 위임_등록의_문자열_campaignId가_새_아이템의_캠페인_연결로_반영된다() {
		// v2 add가 만드는 위임 본문과 같은 셰이프 — campaignId는 String이다(§8 위임 규약).
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("posts", List.of("https://www.instagram.com/p/XYZ789/"));
		body.put("trackingDays", 30);
		body.put("campaignId", String.valueOf(campaignId));

		var created = registrationService.register(userId, body).items();

		assertThat(created).hasSize(1);
		long newItemId = Long.parseLong(created.get(0).id());
		assertThat(itemRepository.findByIdAndUser(newItemId, userId).orElseThrow().campaignId())
				.isEqualTo(campaignId);
	}

	@Test
	void 캠페인_소속_전건_해제_후_재조회는_빈_소속이다() {
		// 같은 shortcode 행 2건이 같은 캠페인에 소속돼도 remove 한 번에 전건이 빠진다(v2 픽스 —
		// 대표 1건만 끊으면 204 직후 재조회에 콘텐츠가 남는다).
		long first = itemRepository.insertPending(userId, "url", UUID.randomUUID(), campaignId, "DUP111",
				"https://www.instagram.com/p/DUP111/", null, 30, LocalDate.now());
		long second = itemRepository.insertPending(userId, "url", UUID.randomUUID(), campaignId, "DUP111",
				"https://www.instagram.com/p/DUP111/", null, 30, LocalDate.now());

		service.remove(userId, campaignId, "DUP111");

		assertThat(itemRepository.findByIdAndUser(first, userId).orElseThrow().campaignId()).isNull();
		assertThat(itemRepository.findByIdAndUser(second, userId).orElseThrow().campaignId()).isNull();
	}
}
