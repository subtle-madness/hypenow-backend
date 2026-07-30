package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.v1.common.V1ApiException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V1CampaignService — resolveOrCreate 두 경로·delete 후 소속 행 해제·patch null 의미론(DB 왕복). */
class V1CampaignServiceTest extends IntegrationTest {

	@Autowired
	V1CampaignService service;
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
				.param("email", "mon-campaign-svc-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void resolveOrCreate_동명_캠페인이_있으면_그_행을_재사용한다() {
		CampaignRow existing = repository.insert(userId, "여름 캠페인", "설명", null, null, null, null);

		V1CampaignService.Resolved resolved = service.resolveOrCreate(userId, "여름 캠페인");

		assertThat(resolved.created()).isFalse();
		assertThat(resolved.row().id()).isEqualTo(existing.id());
		assertThat(resolved.row().description()).isEqualTo("설명"); // 기존 행 그대로, 재생성 아님
	}

	@Test
	void resolveOrCreate_없으면_이름만으로_새로_만든다() {
		V1CampaignService.Resolved resolved = service.resolveOrCreate(userId, "신규 캠페인");

		assertThat(resolved.created()).isTrue();
		assertThat(resolved.row().name()).isEqualTo("신규 캠페인");
		assertThat(resolved.row().description()).isNull();
		assertThat(repository.findByNameAndUser("신규 캠페인", userId)).isPresent();
	}

	@Test
	void resolveOrCreate_공백_축약_후_이름으로_비교한다() {
		repository.insert(userId, "여름 캠페인", null, null, null, null, null);

		V1CampaignService.Resolved resolved = service.resolveOrCreate(userId, "여름   캠페인"); // 연속 공백

		assertThat(resolved.created()).isFalse();
	}

	@Test
	void delete_후_소속_추적_행의_campaignId는_NULL이_된다() {
		CampaignRow campaign = repository.insert(userId, "삭제될캠페인", null, null, null, null, null);
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), campaign.id(), "abc",
				"https://x/abc", null, 30, LocalDate.of(2026, 7, 30));

		service.delete(userId, campaign.id());

		MonitoringItemRow item = itemRepository.findByIdAndUser(itemId, userId).orElseThrow();
		assertThat(item.campaignId()).isNull();
		assertThat(repository.findByIdAndUser(campaign.id(), userId)).isEmpty();
	}

	@Test
	void delete_소유_아니면_404() {
		CampaignRow campaign = repository.insert(userId, "남의캠페인", null, null, null, null, null);
		long otherUserId = jdbcClient
				.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-other-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();

		assertThatThrownBy(() -> service.delete(otherUserId, campaign.id()))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void patch_키가_없으면_기존값을_DB에서도_유지한다() {
		CampaignRow created = repository.insert(userId, "원래이름", "원래설명", LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 2, 1), "원래브랜드", 100L);

		CampaignRow patched = service.patch(userId, created.id(), Map.of("brand", "바뀐브랜드"));

		assertThat(patched.name()).isEqualTo("원래이름"); // 키 없음 = 유지
		assertThat(patched.description()).isEqualTo("원래설명");
		assertThat(patched.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
		assertThat(patched.endDate()).isEqualTo(LocalDate.of(2026, 2, 1));
		assertThat(patched.budget()).isEqualTo(100L);
		assertThat(patched.brand()).isEqualTo("바뀐브랜드");

		CampaignRow reloaded = repository.findByIdAndUser(created.id(), userId).orElseThrow();
		assertThat(reloaded.brand()).isEqualTo("바뀐브랜드");
		assertThat(reloaded.description()).isEqualTo("원래설명");
	}

	@Test
	void patch_키가_있고_null이면_DB에서도_해제된다() {
		CampaignRow created = repository.insert(userId, "원래이름", "원래설명", LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 2, 1), "원래브랜드", 100L);

		Map<String, Object> body = new HashMap<>();
		body.put("description", null);
		body.put("budget", null);
		CampaignRow patched = service.patch(userId, created.id(), body);

		assertThat(patched.description()).isNull();
		assertThat(patched.budget()).isNull();
		assertThat(patched.brand()).isEqualTo("원래브랜드"); // 키 없음 = 유지(같이 검증)

		CampaignRow reloaded = repository.findByIdAndUser(created.id(), userId).orElseThrow();
		assertThat(reloaded.description()).isNull();
		assertThat(reloaded.budget()).isNull();
	}

	@Test
	void patch_이름을_null로_보내면_해제_대신_400이다() {
		CampaignRow created = repository.insert(userId, "원래이름", null, null, null, null, null);

		Map<String, Object> body = new HashMap<>();
		body.put("name", null);

		assertThatThrownBy(() -> service.patch(userId, created.id(), body))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void patch_이름_변경_시_중복이면_409() {
		repository.insert(userId, "선점캠페인", null, null, null, null, null);
		CampaignRow target = repository.insert(userId, "변경대상", null, null, null, null, null);

		assertThatThrownBy(() -> service.patch(userId, target.id(), Map.of("name", "선점캠페인")))
				.isInstanceOf(V1ApiException.class)
				.satisfies(e -> assertThat(((V1ApiException) e).code()).isEqualTo("CAMPAIGN_NAME_EXISTS"));
	}

	@Test
	void patch_없는_캠페인은_404() {
		assertThatThrownBy(() -> service.patch(userId, 987654321L, Map.of("brand", "x")))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void list_는_생성_순서대로_반환한다() {
		CampaignRow c1 = repository.insert(userId, "캠페인1", null, null, null, null, null);
		CampaignRow c2 = repository.insert(userId, "캠페인2", null, null, null, null, null);

		List<CampaignRow> list = service.list(userId);

		assertThat(list).extracting(CampaignRow::id).containsExactly(c1.id(), c2.id());
	}
}
