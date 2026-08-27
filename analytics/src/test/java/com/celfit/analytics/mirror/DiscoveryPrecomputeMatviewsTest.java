package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 발굴 사전집계 matview 3종(V20260827045100) — 정의·round 산식·refresh 반영을 검증한다.
 * 마이그레이션은 WITH DATA로 만들지만 시드가 그 뒤라, 갱신은 DerivedViewRefresher로 수행
 * (CONCURRENTLY 경로 자체를 태운다 — unique index 누락 시 여기서 즉시 실패).
 */
@Testcontainers
class DiscoveryPrecomputeMatviewsTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static JdbcTemplate db;

	@BeforeAll
	static void seed() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		Flyway.configure().dataSource(ds).locations("classpath:db/migration/analysis").load().migrate();
		db = new JdbcTemplate(ds);
		// acc_a 창 6건: 뷰티·분류 5건(makeup 4 + skincare 1 — 80%/20%, 게이트 경계), 비뷰티 1건(a6).
		// a6는 비율 분모(analyzed)에는 잡히고 share 모수에서 빠진다. x1은 미분석 — 어디에도 없음.
		db.update("""
				INSERT INTO account_content_series
				  (short_code, account_handle, posted_at, content_type, views, likes, comments, sponsored)
				VALUES ('a1','acc_a',now(),'reels',100,10,1,false),
				       ('a2','acc_a',now(),'reels',200,20,2,false),
				       ('a3','acc_a',now(),'reels',300,30,3,false),
				       ('a4','acc_a',now(),'feed',NULL,40,4,false),
				       ('a5','acc_a',now(),'reels',500,50,5,false),
				       ('a6','acc_a',now(),'reels',600,60,6,false),
				       ('x1','acc_x',now(),'reels',700,70,7,false)""");
		db.update("""
				INSERT INTO content_analyses (short_code, model, main_category, is_beauty, ad_type)
				VALUES ('a1','t','makeup',true,'sponsored'),
				       ('a2','t','makeup',true,NULL),
				       ('a3','t','makeup',true,NULL),
				       ('a4','t','makeup',true,'sponsored'),
				       ('a5','t','skincare',true,NULL),
				       ('a6','t',NULL,false,NULL)""");
		new DerivedViewRefresher(ds).refresh();
	}

	@Test
	void 뷰티_비율은_분석건수와_뷰티건수를_계정별로_집계한다() {
		Map<String, Object> row = db.queryForMap(
				"SELECT analyzed_count, beauty_count FROM account_beauty_ratio WHERE account_handle = 'acc_a'");
		assertEquals(6L, row.get("analyzed_count"));
		assertEquals(5L, row.get("beauty_count"));
	}

	@Test
	void 카테고리_비중은_게이트와_같은_round_산식이다() {
		List<Map<String, Object>> rows = db.queryForList("""
				SELECT main_category, pct FROM account_category_share
				WHERE account_handle = 'acc_a' ORDER BY main_category""");
		// makeup 4/5 → 80, skincare 1/5 → 20 (경계값: 게이트 ≥20 통과)
		assertEquals(List.of(Map.of("main_category", "makeup", "pct", 80),
				Map.of("main_category", "skincare", "pct", 20)), rows);
	}

	@Test
	void 협찬_수는_ad_type_sponsored만_센다() {
		assertEquals(Integer.valueOf(2), db.queryForObject(
				"SELECT cnt FROM account_sponsored_counts WHERE account_handle = 'acc_a'", Integer.class));
		assertEquals(Integer.valueOf(0), db.queryForObject(
				"SELECT count(*) FROM account_sponsored_counts WHERE account_handle = 'acc_x'", Integer.class));
	}
}
