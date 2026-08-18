package com.celfit.was.monitoring;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 직접 등록 매핑 저장 계층(2026-08-07 스펙 §3-1) — app.brand_direct_posts. 레거시 추적 아이템에
 * "이 게시물은 브랜드 화면 소속" 표식만 붙이는 얇은 테이블이라, 게시물 본체·스냅샷은 전부
 * 레거시 경로(app.monitoring_items·monitoring 서버)가 정본이다.
 *
 * <p>PK가 (user_id, short_code)라 같은 게시물을 두 번 등록해도 매핑은 하나 — 재요청은
 * ON CONFLICT DO NOTHING으로 흡수한다(멱등 replay).
 */
@Repository
public class BrandDirectPostRepository {

	/** app.brand_direct_posts 1행. monitoringItemId는 매핑이 가리키는 레거시 추적 행. */
	public record Row(long userId, long brandId, String shortCode, long monitoringItemId) {
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
	 * 가리키는 레거시 아이템이 캠페인에 배정돼 있고 취소되지 않은 것만. brand_direct_posts·
	 * monitoring_items 둘 다 app 스키마라 크로스 DB 조인이 아니다(was 코드 내 단일 물리 DB 조인).
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
