package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 지문 SQL 스모크(2026-08-13 ETag 설계 §2-3) — 단위 테스트({@link DashboardVersionTest})는 리포지토리를
 * mock하므로 <b>SQL 자체가 도는지·행이 바뀌면 값이 바뀌는지</b>는 여기서만 확인된다.
 *
 * <p>모든 컬럼 조합을 여기서 다 흔들지는 않는다 — 컬럼 대응표의 최종 게이트는 스테이징 검증
 * (설계 §5-⑥, 각 쓰기 직후 200)이다. 여기서 고정하는 것은 (a) 5개 쿼리가 실제 스키마에서 문법·타입
 * 오류 없이 돌고, (b) 행이 없어도 md5를 돌려주며, (c) 대표 변경 하나가 값을 실제로 바꾼다는 것,
 * 그리고 (d) 부착 지문의 <b>브랜드 스코프</b> 계약이다(유저 스코프로 좁히면 조용히 낡은 카드를 준다).
 */
class DashboardVersionRepositoryTest extends IntegrationTest {

	@Autowired
	DashboardVersionRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;
	long campaignId;
	long itemId;
	/** 브랜드 id는 테스트마다 새로 뽑는다 — 부착 지문이 <b>브랜드 스코프</b>라 상수로 고정하면 같은 클래스의
	 * 다른 테스트가 남긴 행이 서로의 기준값을 오염시킨다. */
	long brandId;
	long unlinkedBrandId;

	@BeforeEach
	void 시드() {
		userId = 유저();
		campaignId = 캠페인(userId, "캠페인-" + UUID.randomUUID());
		itemId = 아이템(userId, campaignId, "code" + Math.abs(UUID.randomUUID().hashCode()));
		brandId = 브랜드id();
		unlinkedBrandId = 브랜드id();
		연결(userId, brandId);
	}

	private static long 브랜드id() {
		return ThreadLocalRandom.current().nextLong(1_000_000L, 9_000_000_000L);
	}

	@Test
	void 다섯_쿼리_모두_md5_hex_32자를_돌려준다() {
		assertThat(repository.monitoringItemsFingerprint(userId)).matches("[0-9a-f]{32}");
		assertThat(repository.brandLinksFingerprint(userId)).matches("[0-9a-f]{32}");
		assertThat(repository.directPostsFingerprint(userId)).matches("[0-9a-f]{32}");
		assertThat(repository.campaignsFingerprint(userId)).matches("[0-9a-f]{32}");
		assertThat(repository.postCampaignLinksFingerprint(userId)).matches("[0-9a-f]{32}");
	}

	@Test
	void 행이_하나도_없는_유저도_md5를_돌려준다() {
		long empty = 유저();
		assertThat(repository.monitoringItemsFingerprint(empty)).matches("[0-9a-f]{32}");
		assertThat(repository.brandLinksFingerprint(empty)).matches("[0-9a-f]{32}");
		assertThat(repository.directPostsFingerprint(empty)).matches("[0-9a-f]{32}");
		assertThat(repository.campaignsFingerprint(empty)).matches("[0-9a-f]{32}");
		assertThat(repository.postCampaignLinksFingerprint(empty)).matches("[0-9a-f]{32}");
	}

	@Test
	void 아이템_기간_변경이_지문을_바꾼다() {
		// 이 UPDATE는 어떤 타임스탬프도 갱신하지 않는다(설계 §2-2) — 워터마크 방식이 못 잡는 바로 그 변경.
		String before = repository.monitoringItemsFingerprint(userId);
		jdbcClient.sql("UPDATE app.monitoring_items SET tracking_days = 60 WHERE id = :id")
				.param("id", itemId).update();
		assertThat(repository.monitoringItemsFingerprint(userId)).isNotEqualTo(before);
	}

	@Test
	void 연결_타입_변경과_해제가_지문을_바꾼다() {
		String before = repository.brandLinksFingerprint(userId);
		jdbcClient.sql("UPDATE app.brand_monitorings SET account_type = 'competitor' WHERE user_id = :u")
				.param("u", userId).update();
		String afterType = repository.brandLinksFingerprint(userId);
		assertThat(afterType).isNotEqualTo(before);

		jdbcClient.sql("UPDATE app.brand_monitorings SET deleted_at = now() WHERE user_id = :u")
				.param("u", userId).update();
		assertThat(repository.brandLinksFingerprint(userId)).isNotEqualTo(afterType);
	}

	@Test
	void direct_등록이_지문을_바꾼다() {
		String before = repository.directPostsFingerprint(userId);
		jdbcClient.sql("""
				INSERT INTO app.brand_direct_posts (user_id, brand_id, short_code, monitoring_item_id)
				VALUES (:u, :b, 'directcode', :i)
				""")
				.param("u", userId).param("b", brandId).param("i", itemId).update();
		assertThat(repository.directPostsFingerprint(userId)).isNotEqualTo(before);
	}

	@Test
	void 캠페인_이름_변경이_지문을_바꾼다() {
		String before = repository.campaignsFingerprint(userId);
		jdbcClient.sql("UPDATE app.monitoring_campaigns SET name = :n WHERE id = :id")
				.param("n", "바뀐-" + UUID.randomUUID()).param("id", campaignId).update();
		assertThat(repository.campaignsFingerprint(userId)).isNotEqualTo(before);
	}

	@Test
	void 부착과_해제가_지문을_바꾼다() {
		String before = repository.postCampaignLinksFingerprint(userId);
		부착(userId, brandId, "poolcode", campaignId);
		String attached = repository.postCampaignLinksFingerprint(userId);
		assertThat(attached).isNotEqualTo(before);

		jdbcClient.sql("DELETE FROM app.brand_post_campaigns WHERE brand_id = :b AND short_code = 'poolcode'")
				.param("b", brandId).update();
		assertThat(repository.postCampaignLinksFingerprint(userId)).isEqualTo(before);
	}

	@Test
	void 부착_지문은_같은_브랜드에_붙은_다른_유저의_부착도_잡는다() {
		// 조립이 campaignIdsByCode를 브랜드 스코프로 읽으므로(BrandPostCampaignRepository
		// .findByBrandAndShortCodes) 남의 부착이 내 카드의 campaignId를 바꾼다 — 유저 스코프 지문이면
		// 이 변경을 놓쳐 낡은 카드를 조용히 서빙하게 된다.
		long other = 유저();
		long otherCampaign = 캠페인(other, "남의-" + UUID.randomUUID());
		String before = repository.postCampaignLinksFingerprint(userId);

		부착(other, brandId, "sharedcode", otherCampaign);

		assertThat(repository.postCampaignLinksFingerprint(userId)).isNotEqualTo(before);
	}

	@Test
	void 부착_지문은_연결하지_않은_브랜드의_남의_부착에는_흔들리지_않는다() {
		long other = 유저();
		long otherCampaign = 캠페인(other, "남의-" + UUID.randomUUID());
		String before = repository.postCampaignLinksFingerprint(userId);

		부착(other, unlinkedBrandId, "elsewhere", otherCampaign);   // 내가 연결하지 않은 브랜드

		assertThat(repository.postCampaignLinksFingerprint(userId)).isEqualTo(before);
	}

	// ---------- 시드 헬퍼 ----------

	private long 유저() {
		return jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:e, 'x') RETURNING id")
				.param("e", "dash-version-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	private long 캠페인(long owner, String name) {
		return jdbcClient
				.sql("INSERT INTO app.monitoring_campaigns (user_id, name) VALUES (:u, :n) RETURNING id")
				.param("u", owner).param("n", name)
				.query(Long.class).single();
	}

	private long 아이템(long owner, Long campaign, String inputValue) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_items
				  (user_id, mode, registration_key, campaign_id, input_value, source_url, tracking_days, registered_on)
				VALUES (:u, 'url', :key, :c, :v, :url, 30, DATE '2026-08-01')
				RETURNING id
				""")
				.param("u", owner).param("key", UUID.randomUUID()).param("c", campaign)
				.param("v", inputValue).param("url", "https://www.instagram.com/p/" + inputValue + "/")
				.query(Long.class).single();
	}

	private void 연결(long owner, long brandId) {
		jdbcClient.sql("""
				INSERT INTO app.brand_monitorings (user_id, brand_id, username) VALUES (:u, :b, :n)
				""")
				.param("u", owner).param("b", brandId).param("n", "brand" + brandId).update();
	}

	private void 부착(long owner, long brandId, String shortCode, long campaign) {
		jdbcClient.sql("""
				INSERT INTO app.brand_post_campaigns (brand_id, short_code, campaign_id, user_id)
				VALUES (:b, :s, :c, :u)
				""")
				.param("b", brandId).param("s", shortCode).param("c", campaign).param("u", owner).update();
	}
}
