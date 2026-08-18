package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * app.brand_direct_posts 조회 계층 통합 테스트. {@link BrandDirectPostRepository#findCampaignLinkedShortCodes}는
 * seededAuthor 캠페인 도출(2026-08-18 재설계)의 재료라 브랜드 필터 없이 유저 스코프로 캠페인 연결
 * 매핑만 골라내는지가 핵심 계약이다.
 */
class BrandDirectPostRepositoryTest extends IntegrationTest {

	@Autowired
	BrandDirectPostRepository repository;
	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	CampaignRepository campaignRepository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "brand-direct-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	private long 매핑(String shortCode, Long campaignId) {
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), campaignId, shortCode,
				"https://instagram.com/p/" + shortCode, null, 30, LocalDate.of(2026, 8, 1));
		jdbcClient.sql("""
				INSERT INTO app.brand_direct_posts (user_id, brand_id, short_code, monitoring_item_id)
				VALUES (:userId, 999, :shortCode, :itemId)
				""")
				.param("userId", userId)
				.param("shortCode", shortCode)
				.param("itemId", itemId)
				.update();
		return itemId;
	}

	@Test
	void findCampaignLinkedShortCodes_캠페인_연결된_매핑만_반환한다() {
		CampaignRow campaign = campaignRepository.insert(userId, "캠페인-" + UUID.randomUUID(), null, null, null,
				null, null, null);
		매핑("linked1", campaign.id());

		assertThat(repository.findCampaignLinkedShortCodes(userId)).containsExactly("linked1");
	}

	@Test
	void findCampaignLinkedShortCodes_캠페인_없는_매핑은_제외() {
		매핑("nocampaign", null);

		assertThat(repository.findCampaignLinkedShortCodes(userId)).isEmpty();
	}

	@Test
	void findCampaignLinkedShortCodes_취소된_아이템의_매핑은_제외() {
		CampaignRow campaign = campaignRepository.insert(userId, "캠페인-" + UUID.randomUUID(), null, null, null,
				null, null, null);
		long itemId = 매핑("canceled", campaign.id());
		itemRepository.markCanceled(itemId, "detecting", OffsetDateTime.now());

		assertThat(repository.findCampaignLinkedShortCodes(userId)).isEmpty();
	}

	@Test
	void findCampaignLinkedShortCodes_다른_유저_매핑은_제외() {
		CampaignRow campaign = campaignRepository.insert(userId, "캠페인-" + UUID.randomUUID(), null, null, null,
				null, null, null);
		매핑("mine", campaign.id());

		long otherUserId = jdbcClient
				.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "other-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();

		assertThat(repository.findCampaignLinkedShortCodes(otherUserId)).isEmpty();
	}

	@Test
	void findByUser_동작은_기존과_동일() {
		매핑("plain", null);

		List<BrandDirectPostRepository.Row> rows = repository.findByUser(userId);

		assertThat(rows).extracting(BrandDirectPostRepository.Row::shortCode).containsExactly("plain");
	}
}
