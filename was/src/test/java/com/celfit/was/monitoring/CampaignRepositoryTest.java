package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

class CampaignRepositoryTest extends IntegrationTest {

	@Autowired
	CampaignRepository repository;
	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-campaign-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void insert_RETURNING_값_확인() {
		CampaignRow row = repository.insert(userId, "여름 캠페인", "설명", LocalDate.of(2026, 7, 1),
				LocalDate.of(2026, 8, 31), "브랜드A", 1_000_000L, 40);

		assertThat(row.id()).isPositive();
		assertThat(row.userId()).isEqualTo(userId);
		assertThat(row.name()).isEqualTo("여름 캠페인");
		assertThat(row.description()).isEqualTo("설명");
		assertThat(row.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
		assertThat(row.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
		assertThat(row.brand()).isEqualTo("브랜드A");
		assertThat(row.budget()).isEqualTo(1_000_000L);
		assertThat(row.seedingCount()).isEqualTo(40);
		assertThat(row.createdAt()).isNotNull();
	}

	@Test
	void 이름_유니크_충돌은_DuplicateKeyException() {
		repository.insert(userId, "중복 캠페인", null, null, null, null, null, null);

		assertThatThrownBy(() -> repository.insert(userId, "중복 캠페인", null, null, null, null, null, null))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void findByUser_정렬은_created_at_ASC_id_ASC() {
		CampaignRow c1 = repository.insert(userId, "캠페인1", null, null, null, null, null, null);
		CampaignRow c2 = repository.insert(userId, "캠페인2", null, null, null, null, null, null);

		List<CampaignRow> byUser = repository.findByUser(userId);
		assertThat(byUser).extracting(CampaignRow::id).containsExactly(c1.id(), c2.id());
	}

	@Test
	void findByNameAndUser() {
		repository.insert(userId, "찾는캠페인", null, null, null, null, null, null);

		assertThat(repository.findByNameAndUser("찾는캠페인", userId)).isPresent();
		assertThat(repository.findByNameAndUser("없는캠페인", userId)).isEmpty();
	}

	@Test
	void update_왕복() {
		CampaignRow created = repository.insert(userId, "원래이름", "원래설명", LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 2, 1), "원래브랜드", 100L, 40);

		CampaignRow updated = repository.update(created.id(), "바뀐이름", "바뀐설명", LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 4, 1), "바뀐브랜드", 200L, 80);

		assertThat(updated.name()).isEqualTo("바뀐이름");
		assertThat(updated.description()).isEqualTo("바뀐설명");
		assertThat(updated.startDate()).isEqualTo(LocalDate.of(2026, 3, 1));
		assertThat(updated.endDate()).isEqualTo(LocalDate.of(2026, 4, 1));
		assertThat(updated.brand()).isEqualTo("바뀐브랜드");
		assertThat(updated.budget()).isEqualTo(200L);
		assertThat(updated.seedingCount()).isEqualTo(80);

		CampaignRow reloaded = repository.findByIdAndUser(created.id(), userId).orElseThrow();
		assertThat(reloaded.name()).isEqualTo("바뀐이름");
	}

	@Test
	void update_이름_유니크_충돌_전파() {
		repository.insert(userId, "선점캠페인", null, null, null, null, null, null);
		CampaignRow target = repository.insert(userId, "변경대상", null, null, null, null, null, null);

		assertThatThrownBy(() -> repository.update(target.id(), "선점캠페인", null, null, null, null, null, null))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void delete_후_배정된_items의_campaign_id는_NULL() {
		CampaignRow campaign = repository.insert(userId, "삭제될캠페인", null, null, null, null, null, null);
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), campaign.id(), "abc",
				"https://x/abc", null, 30, LocalDate.of(2026, 7, 30));

		assertThat(repository.countItems(campaign.id())).isEqualTo(1);

		repository.delete(campaign.id());

		// item은 삭제되지 않고 남는다 — campaign_id만 SET NULL로 풀린다(CampaignRepository.delete
		// 주석의 전제). item은 살아있는데도 아카이브되면 그 자체가 결함(멀쩡한 행이 사라진 것처럼
		// 아카이브에 잘못 기록됨)이라 이 테스트가 그 전제를 실제로 실행해서 검증한다.
		MonitoringItemRow item = itemRepository.findByIdAndUser(itemId, userId).orElseThrow();
		assertThat(item.campaignId()).isNull();
		assertThat(repository.findByIdAndUser(campaign.id(), userId)).isEmpty();

		List<String> archivedTables = jdbcClient.sql("""
						SELECT table_name FROM archive.archived_rows WHERE user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.list();
		assertThat(archivedTables).containsExactly("app.monitoring_campaigns");
	}

	@Test
	void 캠페인을_삭제하면_아카이브에_남는다() {
		CampaignRow campaign = repository.insert(userId, "여름 캠페인", null, null, null, null, null, null);

		repository.delete(campaign.id());

		String name = jdbcClient.sql("""
						SELECT payload ->> 'name' FROM archive.archived_rows
						 WHERE table_name = 'app.monitoring_campaigns' AND user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.single();

		assertThat(name).isEqualTo("여름 캠페인");
		assertThat(jdbcClient.sql("SELECT count(*) FROM app.monitoring_campaigns WHERE id = :id")
				.param("id", campaign.id())
				.query(Long.class)
				.single()).isZero();
	}

	/**
	 * 2026-08-18 direct 통합 §T13 — brand_post_campaigns는 campaign_id FK에 CASCADE가 없어 캠페인
	 * 삭제 전에 명시적으로 먼저 아카이브·삭제해야 한다(안 그러면 FK 위반). 이 테스트는 그 순서가
	 * 실제로 지켜지는지 + 두 테이블 모두 아카이브에 남는지를 함께 고정한다.
	 */
	@Test
	void delete는_brand_post_campaigns_링크를_먼저_아카이브하고_지운다() {
		CampaignRow campaign = repository.insert(userId, "브랜드연동캠페인", null, null, null, null, null, null);
		jdbcClient.sql("""
						INSERT INTO app.brand_post_campaigns (brand_id, short_code, campaign_id, user_id)
						VALUES (:brandId, :shortCode, :campaignId, :userId)
						""")
				.param("brandId", 1L).param("shortCode", "ABC").param("campaignId", campaign.id())
				.param("userId", userId)
				.update();

		repository.delete(campaign.id());

		assertThat(jdbcClient.sql("SELECT count(*) FROM app.brand_post_campaigns WHERE campaign_id = :id")
				.param("id", campaign.id()).query(Long.class).single()).isZero();
		assertThat(jdbcClient.sql("SELECT count(*) FROM app.monitoring_campaigns WHERE id = :id")
				.param("id", campaign.id()).query(Long.class).single()).isZero();
		List<String> archivedTables = jdbcClient.sql("""
						SELECT table_name FROM archive.archived_rows WHERE user_id = :id ORDER BY table_name
						""")
				.param("id", userId)
				.query(String.class)
				.list();
		assertThat(archivedTables).containsExactlyInAnyOrder("app.monitoring_campaigns", "app.brand_post_campaigns");
	}

	/** 링크가 없는 캠페인 삭제는 브랜드 풀 아카이브 없이 캠페인 1건만 남는다(회귀 방지 — 링크 순회가 no-op이어야 함). */
	@Test
	void 링크_없는_캠페인_삭제는_brand_post_campaigns를_건드리지_않는다() {
		CampaignRow campaign = repository.insert(userId, "링크없는캠페인", null, null, null, null, null, null);

		repository.delete(campaign.id());

		List<String> archivedTables = jdbcClient.sql("""
						SELECT table_name FROM archive.archived_rows WHERE user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.list();
		assertThat(archivedTables).containsExactly("app.monitoring_campaigns");
	}

	@Test
	void countItems() {
		CampaignRow campaign = repository.insert(userId, "카운트캠페인", null, null, null, null, null, null);
		assertThat(repository.countItems(campaign.id())).isZero();

		itemRepository.insertPending(userId, "url", UUID.randomUUID(), campaign.id(), "a", "https://x/a", null, 30,
				LocalDate.of(2026, 7, 30));
		itemRepository.insertPending(userId, "url", UUID.randomUUID(), campaign.id(), "b", "https://x/b", null, 30,
				LocalDate.of(2026, 7, 30));

		assertThat(repository.countItems(campaign.id())).isEqualTo(2);
	}
}
