package com.celfit.was.monitoring;

import java.util.LinkedHashSet;
import java.util.List;
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
}
