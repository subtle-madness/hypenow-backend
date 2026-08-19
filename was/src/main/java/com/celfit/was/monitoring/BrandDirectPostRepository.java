package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 직접 등록 매핑 저장 계층(2026-08-07 스펙 §3-1, 2026-08-18 direct 통합 §1-1로 성격 변경) —
 * app.brand_direct_posts. 통합 이전에는 레거시 추적 아이템에 "이 게시물은 브랜드 화면 소속" 표식만
 * 붙이는 얇은 테이블이었으나, 통합 후에는 <b>유저 귀속 원장</b>으로 성격이 바뀐다 — 서빙 정본은
 * monitoring 브랜드 풀(brand_tagged_post)이고, 이 테이블은 "누가 등록했는가"만 보존한다(탈퇴
 * 아카이브 대상 유지, 향후 "등록자 표시" 여지). {@code migrated_at}이 채워진 행(신규 등록 전부)은
 * 서빙 조인에서 빠진다 — 레거시 조립 대상은 {@code migrated_at IS NULL}인 이관 전 매핑뿐이다(T10).
 *
 * <p>PK가 (user_id, short_code)라 같은 게시물을 두 번 등록해도 매핑은 하나 — 재요청은
 * ON CONFLICT DO NOTHING으로 흡수한다(멱등 replay).
 */
@Repository
public class BrandDirectPostRepository {

	/**
	 * app.brand_direct_posts 1행. monitoringItemId는 레거시 매핑이 가리키는 추적 행 —
	 * {@code migrated_at IS NULL}인 이관 전 행에만 값이 있다. 2026-08-18부터 컬럼이 nullable이라
	 * (신규 direct 통합 등록은 레거시 아이템을 만들지 않는다) Long으로 넓혔다 — 기존 primitive long은
	 * NULL 매핑을 읽을 때 매핑 자체가 실패한다.
	 */
	public record Row(long userId, long brandId, String shortCode, Long monitoringItemId) {
	}

	private final JdbcClient jdbcClient;

	public BrandDirectPostRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 유저의 직접 등록 매핑 전량 — 브랜드 필터링(brandId 교집합)은 호출부 몫. */
	public List<Row> findByUser(long userId) {
		return jdbcClient.sql("""
				SELECT user_id, brand_id, short_code, monitoring_item_id
				FROM app.brand_direct_posts
				WHERE user_id = :userId
				ORDER BY short_code ASC
				""")
				.param("userId", userId)
				.query(Row.class)
				.list();
	}

	/** 매핑 등록 — 이미 있으면 무시(기존 행 유지). */
	public void upsert(long userId, long brandId, String shortCode, long itemId) {
		jdbcClient.sql("""
				INSERT INTO app.brand_direct_posts (user_id, brand_id, short_code, monitoring_item_id)
				VALUES (:userId, :brandId, :shortCode, :itemId)
				ON CONFLICT (user_id, short_code) DO NOTHING
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("shortCode", shortCode)
				.param("itemId", itemId)
				.update();
	}

	/**
	 * 캠페인 연결된 직접 등록 게시물 shortcode(seededAuthor 캠페인 도출 재료, 2026-08-18) — 매핑이
	 * 가리키는 레거시 아이템이 캠페인에 배정돼 있고 취소되지 않은 것만. {@code monitoring_item_id}가
	 * NULL인 행(direct 통합 이후 신규 등록 — §T8)은 이 조회 대상이 아니다: 캠페인 연결은
	 * {@code app.brand_post_campaigns}로 옮겨졌으므로(설계 §결정 3) 그쪽은 seededAuthor 산출에서
	 * {@code BrandPostCampaignRepository}가 별도로 커버한다. brand_direct_posts·monitoring_items
	 * 둘 다 app 스키마라 크로스 DB 조인이 아니다(was 코드 내 단일 물리 DB 조인).
	 */
	public List<String> findCampaignLinkedShortCodes(long userId) {
		return jdbcClient.sql("""
				SELECT d.short_code
				FROM app.brand_direct_posts d
				JOIN app.monitoring_items m ON m.id = d.monitoring_item_id
				WHERE d.user_id = :userId AND m.campaign_id IS NOT NULL AND m.canceled_at IS NULL
				""")
				.param("userId", userId)
				.query(String.class)
				.list();
	}

	/**
	 * direct 통합 등록(2026-08-18 §T8) — 원장 행만 남긴다. {@code monitoring_item_id}는 NULL(레거시
	 * 아이템 없음), {@code migrated_at}은 now()로 즉시 찍는다("이 매핑의 정본은 monitoring 통합
	 * 풀이다" — 레거시 조립 대상이 아님, 설계 §4-1). 이미 있으면 무시(멱등 — stale 복구 재시도 안전).
	 */
	public void upsertDirect(long userId, long brandId, String shortCode) {
		jdbcClient.sql("""
				INSERT INTO app.brand_direct_posts (user_id, brand_id, short_code, monitoring_item_id, migrated_at)
				VALUES (:userId, :brandId, :shortCode, NULL, now())
				ON CONFLICT (user_id, short_code) DO NOTHING
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("shortCode", shortCode)
				.update();
	}

	/**
	 * 이관 전 매핑만(migrated_at IS NULL, 2026-08-18 direct 통합 §T10·§4-1) — {@code BrandPostAssembler}의
	 * 과도기 폴백({@code assembleLegacyPending}) 전용. 이관 잡(M2)이 진행되는 만큼 자연히 비어가고,
	 * contract 단계에서 이 메서드와 호출부를 함께 제거한다.
	 */
	public List<Row> findPendingByUser(long userId) {
		return jdbcClient.sql("""
				SELECT user_id, brand_id, short_code, monitoring_item_id
				FROM app.brand_direct_posts
				WHERE user_id = :userId AND migrated_at IS NULL
				ORDER BY short_code ASC
				""")
				.param("userId", userId)
				.query(Row.class)
				.list();
	}

	/**
	 * 이관 잡(M2) 전용 1행 — {@code createdAt}은 monitoring 등록 호출의 {@code registeredAt}(원 등록
	 * 시점)으로 쓴다. {@code campaignId}는 원 매핑이 가리키는 레거시 아이템의 캠페인 연결(있으면)이다
	 * — 이관 성공 시 {@code app.brand_post_campaigns}로 옮겨진다. monitoring_item_id가 NULL(신규
	 * 통합 등록)인 행은 애초에 migrated_at이 즉시 찍혀 이 조회 대상이 아니므로 항상 값이 있다.
	 */
	public record PendingMigrationRow(long userId, long brandId, String shortCode, OffsetDateTime createdAt,
			Long campaignId) {
	}

	/**
	 * 이관 잡(M2) 전용 — {@code migrated_at IS NULL}인 행 전체를 유저 스코프 없이 조회한다.
	 * 브랜드별 shortcode dedupe는 호출부(잡)가 한다(같은 게시물을 여러 유저가 등록했을 수 있다).
	 * {@code monitoring_items}와 LEFT JOIN해 캠페인 연결을 한 왕복에 같이 가져온다(N+1 회피).
	 */
	public List<PendingMigrationRow> findAllPending() {
		return jdbcClient.sql("""
				SELECT p.user_id, p.brand_id, p.short_code, p.created_at, i.campaign_id
				FROM app.brand_direct_posts p
				LEFT JOIN app.monitoring_items i ON i.id = p.monitoring_item_id
				WHERE p.migrated_at IS NULL
				ORDER BY p.brand_id, p.short_code, p.user_id
				""")
				.query(PendingMigrationRow.class)
				.list();
	}

	/**
	 * 이관 잡(M2) 전용 — 성공·404·422 정산 시 {@code migrated_at}을 now()로 찍는다(무한 재시도 방지).
	 * 같은 게시물을 여러 유저가 등록했으면 그 전부가 한 번에 정산된다(브랜드별 shortcode dedupe와 짝).
	 */
	public void markMigrated(long brandId, String shortCode) {
		jdbcClient.sql("""
				UPDATE app.brand_direct_posts SET migrated_at = now()
				WHERE brand_id = :brandId AND short_code = :shortCode AND migrated_at IS NULL
				""")
				.param("brandId", brandId)
				.param("shortCode", shortCode)
				.update();
	}

	/** 레거시 목록에서 direct 소속을 가려내기 위한 shortcode 집합(브랜드 무관 — 유저 스코프). */
	public Set<String> shortCodesByUser(long userId) {
		return new LinkedHashSet<>(jdbcClient.sql("""
				SELECT short_code FROM app.brand_direct_posts
				WHERE user_id = :userId
				ORDER BY short_code ASC
				""")
				.param("userId", userId)
				.query(String.class)
				.list());
	}

	/** 취소 대상 매핑 단건 조회(2026-08-17 취소 API) — PK(user_id, short_code)로 직접 찾는다. */
	public Optional<Row> findByUserAndShortCode(long userId, String shortCode) {
		return jdbcClient.sql("""
				SELECT user_id, brand_id, short_code, monitoring_item_id
				FROM app.brand_direct_posts
				WHERE user_id = :userId AND short_code = :shortCode
				""")
				.param("userId", userId)
				.param("shortCode", shortCode)
				.query(Row.class)
				.optional();
	}

	/**
	 * 등록자 한정 취소 시맨틱(요구사항, 08-19) — 이 (brand, shortcode)에 excludingUserId 말고 다른
	 * 등록자가 남아 있는지. 정상 경로에서는 등록 접수 시점 판정(브랜드 풀 중복 체크)이 같은 shortcode의
	 * 두 번째 등록을 duplicate로 막지만, 접수 시점 검사와 실행기 처리 시점 재검사({@code
	 * BrandDirectRegistrationExecutor#isAlreadyDirectRegistered}) 사이는 원자적이지 않아 동시 등록
	 * 요청이 그 창을 통과하면 서로 다른 유저가 독립적으로 같은 (brand, shortcode)를 등록하는 상태가
	 * 실제로 가능하다 — PK가 (user_id, short_code)라 DB가 이를 막지 않는다. 취소가 브랜드 풀의 direct
	 * 표식을 해제하기 전에 반드시 이 조회로 다른 등록자 존재를 확인해야 한다: 있으면 표식을 건드리지
	 * 않아야 한 유저의 취소가 다른 유저의 화면에서 게시물을 지우는 사고를 막는다.
	 */
	public boolean hasOtherRegistrant(long brandId, String shortCode, long excludingUserId) {
		Boolean exists = jdbcClient.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM app.brand_direct_posts
				    WHERE brand_id = :brandId AND short_code = :shortCode AND user_id <> :userId
				)
				""")
				.param("brandId", brandId)
				.param("shortCode", shortCode)
				.param("userId", excludingUserId)
				.query(Boolean.class)
				.single();
		return Boolean.TRUE.equals(exists);
	}

	/**
	 * 매핑 삭제(hard delete, 취소 API 계약) — 삭제로 GET .../posts 목록에서 즉시 빠지고, 같은
	 * shortcode 재등록이 브랜드 중복 판정({@code brandShortCodes})에 걸리지 않게 된다(취소 후
	 * 재시작 성립). tombstone이 아닌 이유: 재등록이 곧 새 매핑이라 삭제 이력을 남길 필요가 없다.
	 */
	public void delete(long userId, String shortCode) {
		jdbcClient.sql("""
				DELETE FROM app.brand_direct_posts WHERE user_id = :userId AND short_code = :shortCode
				""")
				.param("userId", userId)
				.param("shortCode", shortCode)
				.update();
	}
}
