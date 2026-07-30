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
		CampaignRow existing = repository.insert(userId, "여름 캠페인", "설명", null, null, null, null, null);

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
		repository.insert(userId, "여름 캠페인", null, null, null, null, null, null);

		V1CampaignService.Resolved resolved = service.resolveOrCreate(userId, "여름   캠페인"); // 연속 공백

		assertThat(resolved.created()).isFalse();
	}

	@Test
	void delete_후_소속_추적_행의_campaignId는_NULL이_된다() {
		CampaignRow campaign = repository.insert(userId, "삭제될캠페인", null, null, null, null, null, null);
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), campaign.id(), "abc",
				"https://x/abc", null, 30, LocalDate.of(2026, 7, 30));

		service.delete(userId, campaign.id());

		MonitoringItemRow item = itemRepository.findByIdAndUser(itemId, userId).orElseThrow();
		assertThat(item.campaignId()).isNull();
		assertThat(repository.findByIdAndUser(campaign.id(), userId)).isEmpty();
	}

	@Test
	void delete_소유_아니면_404() {
		CampaignRow campaign = repository.insert(userId, "남의캠페인", null, null, null, null, null, null);
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
				LocalDate.of(2026, 2, 1), "원래브랜드", 100L, 40);

		CampaignRow patched = service.patch(userId, created.id(), Map.of("brand", "바뀐브랜드"));

		assertThat(patched.name()).isEqualTo("원래이름"); // 키 없음 = 유지
		assertThat(patched.description()).isEqualTo("원래설명");
		assertThat(patched.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
		assertThat(patched.endDate()).isEqualTo(LocalDate.of(2026, 2, 1));
		assertThat(patched.budget()).isEqualTo(100L);
		assertThat(patched.seedingCount()).isEqualTo(40); // 키 없음 = 유지(같이 검증)
		assertThat(patched.brand()).isEqualTo("바뀐브랜드");

		CampaignRow reloaded = repository.findByIdAndUser(created.id(), userId).orElseThrow();
		assertThat(reloaded.brand()).isEqualTo("바뀐브랜드");
		assertThat(reloaded.description()).isEqualTo("원래설명");
	}

	@Test
	void patch_키가_있고_null이면_DB에서도_해제된다() {
		CampaignRow created = repository.insert(userId, "원래이름", "원래설명", LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 2, 1), "원래브랜드", 100L, 40);

		Map<String, Object> body = new HashMap<>();
		body.put("description", null);
		body.put("budget", null);
		body.put("seedingCount", null);
		CampaignRow patched = service.patch(userId, created.id(), body);

		assertThat(patched.description()).isNull();
		assertThat(patched.budget()).isNull();
		assertThat(patched.seedingCount()).isNull();
		assertThat(patched.brand()).isEqualTo("원래브랜드"); // 키 없음 = 유지(같이 검증)

		CampaignRow reloaded = repository.findByIdAndUser(created.id(), userId).orElseThrow();
		assertThat(reloaded.description()).isNull();
		assertThat(reloaded.budget()).isNull();
		assertThat(reloaded.seedingCount()).isNull();
	}

	@Test
	void patch_이름을_null로_보내면_해제_대신_400이다() {
		CampaignRow created = repository.insert(userId, "원래이름", null, null, null, null, null, null);

		Map<String, Object> body = new HashMap<>();
		body.put("name", null);

		assertThatThrownBy(() -> service.patch(userId, created.id(), body))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void patch_이름_변경_시_중복이면_409() {
		repository.insert(userId, "선점캠페인", null, null, null, null, null, null);
		CampaignRow target = repository.insert(userId, "변경대상", null, null, null, null, null, null);

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
		CampaignRow c1 = repository.insert(userId, "캠페인1", null, null, null, null, null, null);
		CampaignRow c2 = repository.insert(userId, "캠페인2", null, null, null, null, null, null);

		List<CampaignRow> list = service.list(userId);

		assertThat(list).extracting(CampaignRow::id).containsExactly(c1.id(), c2.id());
	}

	@Test
	void create_시작일이_종료일보다_늦으면_400() {
		assertThatThrownBy(
				() -> service.create(userId, "기간역전캠페인", null, "2026-08-31", "2026-07-01", null, null, null))
				.isInstanceOf(V1ApiException.class);

		assertThat(repository.findByNameAndUser("기간역전캠페인", userId)).isEmpty();
	}

	@Test
	void patch_기존_종료일보다_늦은_시작일만_주면_400() {
		// 기존 기간 2026-01-01~2026-02-01. endDate는 그대로 두고 startDate만 그보다 늦게 patch하면
		// 머지된 (startDate, 기존 endDate) 조합이 역전되므로 400이어야 한다(회귀 안전망).
		CampaignRow created = repository.insert(userId, "기간패치캠페인", null, LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 2, 1), null, null, null);

		Map<String, Object> body = new HashMap<>();
		body.put("startDate", "2026-03-01"); // 기존 endDate(2026-02-01)보다 늦음

		assertThatThrownBy(() -> service.patch(userId, created.id(), body))
				.isInstanceOf(V1ApiException.class);

		CampaignRow reloaded = repository.findByIdAndUser(created.id(), userId).orElseThrow();
		assertThat(reloaded.startDate()).isEqualTo(LocalDate.of(2026, 1, 1)); // DB는 변경되지 않음
	}

	@Test
	void create_설명_200자는_통과한다() {
		String description = "가".repeat(200);

		CampaignRow row = service.create(userId, "설명이백자캠페인", description, null, null, null, null, null);

		assertThat(row.description()).hasSize(200);
	}

	@Test
	void create_설명_201자는_거부한다() {
		String description = "가".repeat(201);

		assertThatThrownBy(
				() -> service.create(userId, "설명이백일자캠페인", description, null, null, null, null, null))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void create_브랜드_30자는_통과한다() {
		String brand = "가".repeat(30);

		CampaignRow row = service.create(userId, "브랜드삼십자캠페인", null, null, null, brand, null, null);

		assertThat(row.brand()).hasSize(30);
	}

	@Test
	void create_브랜드_31자는_거부한다() {
		String brand = "가".repeat(31);

		assertThatThrownBy(
				() -> service.create(userId, "브랜드삼십일자캠페인", null, null, null, brand, null, null))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void create_budget_음수는_400() {
		assertThatThrownBy(() -> service.create(userId, "예산음수캠페인", null, null, null, null, -1, null))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void create_budget_문자열은_정수가_아니라서_400() {
		// 스펙(6.25)은 budget을 "원 단위 0 이상 정수"로만 받는다 — JSON에 문자열로 온 "300"은
		// 타입 자체가 정수가 아니므로 거부한다(현재 parseBudget 로직: Integer/Long만 허용).
		assertThatThrownBy(() -> service.create(userId, "예산문자열캠페인", null, null, null, null, "300", null))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void create_seedingCount가_있으면_응답과_DB에_반영된다() {
		CampaignRow row = service.create(userId, "시딩인원캠페인", null, null, null, null, null, 40);

		assertThat(row.seedingCount()).isEqualTo(40);
		CampaignRow reloaded = repository.findByIdAndUser(row.id(), userId).orElseThrow();
		assertThat(reloaded.seedingCount()).isEqualTo(40);
	}

	@Test
	void create_seedingCount를_생략하면_null이다() {
		CampaignRow row = service.create(userId, "시딩인원생략캠페인", null, null, null, null, null, null);

		assertThat(row.seedingCount()).isNull();
	}

	@Test
	void create_seedingCount_음수는_400_한국어_메시지() {
		assertThatThrownBy(() -> service.create(userId, "시딩인원음수캠페인", null, null, null, null, null, -1))
				.isInstanceOf(V1ApiException.class)
				.satisfies(e -> assertThat(((V1ApiException) e).getMessage())
						.isEqualTo("시딩 인원은 0 이상의 정수로 입력해 주세요."));
	}

	@Test
	void create_seedingCount_소수는_400() {
		// Map<String,Object> 역직렬화에서 JSON 소수는 Double로 들어온다(budget과 동일한 타입 판별 방식).
		assertThatThrownBy(() -> service.create(userId, "시딩인원소수캠페인", null, null, null, null, null, 12.5))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void patch_seedingCount_음수는_400이고_DB는_변경되지_않는다() {
		CampaignRow created = repository.insert(userId, "시딩패치캠페인", null, null, null, null, null, 40);

		assertThatThrownBy(() -> service.patch(userId, created.id(), Map.of("seedingCount", -1)))
				.isInstanceOf(V1ApiException.class);

		CampaignRow reloaded = repository.findByIdAndUser(created.id(), userId).orElseThrow();
		assertThat(reloaded.seedingCount()).isEqualTo(40); // DB는 변경되지 않음
	}
}
