package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
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
 * 피어 퍼센타일 뷰(V39) 계약:
 * ① 피어 그룹 = 최빈 main_group × 팔로워 버킷 ② percent_rank는 값 큰 쪽이 0(상위)
 * ③ NULL 지표(피드 전용 avg_views 등)는 순위에서 제외돼 NULL
 * ④ 광고 지표는 ad_type='sponsored' 정본 ⑤ 중앙값 ER(피어·전체) 노출.
 */
@Testcontainers
class AccountPeerStatsViewTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static JdbcTemplate db;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		Flyway.configure().dataSource(ds).locations("classpath:db/migration/analysis").load().migrate();
		db = new JdbcTemplate(ds);
		// 같은 버킷(1만-5만)·같은 카테고리(스킨케어) 4계정 — avg_views 50k/30k/10k/NULL.
		db.update("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers) VALUES
				  ('a', 'A', NULL, 20000), ('b', 'B', NULL, 30000), ('c', 'C', NULL, 40000),
				  ('d', 'D', NULL, 25000)""");
		db.update("""
				INSERT INTO account_summaries (handle, followers, analyzed_count, views_count, metric,
				  avg_views, avg_er_pct, avg_likes, avg_comments) VALUES
				  ('a', 20000, 12, 6, 'views', 50000, 4.0, 3000, 150),
				  ('b', 30000, 12, 6, 'views', 30000, 3.0, 2000, 100),
				  ('c', 40000, 12, 6, 'views', 10000, 2.0, 1000, 50),
				  ('d', 25000, 12, 0, 'likes', NULL,  1.0,  500, 20)""");
		// 주 카테고리: 전원 스킨케어(최빈). content_analyses 시드로 카테고리·광고를 같이 만든다.
		db.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
				  views, likes, comments, sponsored) VALUES
				  ('a1', 'a', now() - interval '3 days', 'reels', 60000, 3500, 160, false),
				  ('a2', 'a', now() - interval '2 days', 'reels', 40000, 2500, 140, false),
				  ('b1', 'b', now() - interval '3 days', 'reels', 30000, 2000, 100, false),
				  ('c1', 'c', now() - interval '3 days', 'reels', 10000, 1000, 50, false),
				  ('d1', 'd', now() - interval '3 days', 'feed',  NULL,   500, 20, false)""");
		db.update("""
				INSERT INTO content_analyses (short_code, analyzed_at, model, is_beauty, main_category, ad_type)
				VALUES
				  ('a1', now(), 'm', true, 'skincare', 'sponsored'),
				  ('a2', now(), 'm', true, 'skincare', 'organic'),
				  ('b1', now(), 'm', true, 'skincare', 'organic'),
				  ('c1', now(), 'm', true, 'skincare', 'organic'),
				  ('d1', now(), 'm', true, 'skincare', 'organic')""");

		// 중앙값 분리 검증용: a의 피어 그룹(스킨케어·1만-5만)과 겹치지 않는 계정들 —
		// 전체(global) 중앙값만 끌어올려 피어 중앙값과 값이 달라지게 한다.
		db.update("""
				INSERT INTO account_summaries (handle, followers, avg_er_pct) VALUES
				  ('e', 60000,  10.0),
				  ('f', 70000,  20.0),
				  ('h', 500000,  6.0),
				  ('i', NULL,    7.0)""");

		// g: 스킨케어 2건 + 메이크업 1건 → 최빈 카테고리(스킨케어)로 분류돼야 한다.
		// 팔로워(15만)는 10만-50만 버킷이라 a의 피어 그룹(1만-5만)과도 안 겹친다.
		db.update("INSERT INTO account_summaries (handle, followers, avg_er_pct) VALUES ('g', 150000, 5.0)");
		db.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
				  views, likes, comments, sponsored) VALUES
				  ('g1', 'g', now() - interval '3 days', 'reels', 5000, 300, 20, false),
				  ('g2', 'g', now() - interval '2 days', 'reels', 4000, 250, 15, false),
				  ('g3', 'g', now() - interval '1 days', 'reels', 3000, 200, 10, false)""");
		db.update("""
				INSERT INTO content_analyses (short_code, analyzed_at, model, is_beauty, main_category, ad_type)
				VALUES
				  ('g1', now(), 'm', true, 'skincare', 'organic'),
				  ('g2', now(), 'm', true, 'skincare', 'organic'),
				  ('g3', now(), 'm', true, 'makeup',   'organic')""");
		// h·i는 account_content_series가 아예 없다 — account_category_stats에 행이 안 생겨
		// peer_category가 '미분류'로 폴백한다(h는 팔로워 있음, i는 팔로워 NULL → '미상' 버킷).

		// F&B 단독 계정 k — 축 뷰 검증용. avg_er_pct NULL: 기존 중앙값 테스트의 전체 모수(9곳)를
		// 흔들지 않기 위해(percentile_cont는 NULL 제외). snack 2건 > convenience 1건 → fnb 축 최빈 간식류.
		db.update("INSERT INTO account_summaries (handle, followers) VALUES ('k', 20000)");
		db.update("""
				INSERT INTO account_content_series (short_code, account_handle, posted_at, content_type,
				  views, likes, comments, sponsored) VALUES
				  ('k1', 'k', now() - interval '3 days', 'reels', 8000, 400, 30, false),
				  ('k2', 'k', now() - interval '2 days', 'reels', 7000, 350, 25, false),
				  ('k3', 'k', now() - interval '1 days', 'reels', 6000, 300, 20, false)""");
		db.update("""
				INSERT INTO content_analyses (short_code, analyzed_at, model, is_beauty, main_category, ad_type)
				VALUES
				  ('k1', now(), 'm', false, 'snack', 'organic'),
				  ('k2', now(), 'm', false, 'snack', 'organic'),
				  ('k3', now(), 'm', false, 'convenience', 'organic')""");
	}

	@Test
	void 피어_그룹과_퍼센타일() {
		Map<String, Object> a = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'a'");
		assertEquals("스킨케어", a.get("peer_category")); // beauty_taxonomy main_label
		assertEquals("1만-5만", a.get("follower_bucket"));
		assertEquals(4L, a.get("peer_size"));
		assertEquals(0, a.get("top_pct_views"));   // avg_views 1위 → percent_rank 0
		Map<String, Object> c = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'c'");
		assertEquals(100, c.get("top_pct_views")); // 3계정 중 꼴찌 → 100
	}

	@Test
	void NULL_지표는_순위에서_제외() {
		Map<String, Object> d = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'd'");
		assertNull(d.get("top_pct_views"));
		// er 순위(4.0/3.0/2.0/1.0 중 1.0)는 있어야 한다
		assertEquals(100, d.get("top_pct_er"));
	}

	@Test
	void 광고_지표는_ad_type_정본으로_계산() {
		Map<String, Object> a = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'a'");
		assertEquals(0, a.get("top_pct_ad_views")); // 광고 게시물 보유 계정이 a뿐 → 단독 1위
		Map<String, Object> b = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'b'");
		assertNull(b.get("top_pct_ad_views"));      // 광고 없음 → NULL
	}

	@Test
	void 중앙값_ER() {
		Map<String, Object> a = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'a'");
		BigDecimal peerMedian = (BigDecimal) a.get("peer_median_er_pct");
		BigDecimal globalMedian = (BigDecimal) a.get("global_median_er_pct");
		// 피어(스킨케어·1만-5만) 4곳: 4.0, 3.0, 2.0, 1.0 → 중앙값 2.5
		assertEquals(0, new BigDecimal("2.5").compareTo(peerMedian));
		// 전체 9곳(a~i): 1,2,3,4,5,6,7,10,20 → 중앙값(5번째 값) 5.0 — 피어와 값이 달라야 한다
		assertEquals(0, new BigDecimal("5.0").compareTo(globalMedian));
		assertNotEquals(0, peerMedian.compareTo(globalMedian));
	}

	@Test
	void 여러_카테고리를_가진_계정은_최빈_카테고리로_분류된다() {
		// g는 스킨케어 2건 + 메이크업 1건 → 최빈(content_count DESC)인 스킨케어로 분류돼야 한다.
		Map<String, Object> g = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'g'");
		assertEquals("스킨케어", g.get("peer_category"));
	}

	@Test
	void 카테고리_데이터가_없는_계정은_미분류로_폴백한다() {
		// h는 account_content_series가 없어 account_category_stats에 행이 없다.
		Map<String, Object> h = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'h'");
		assertEquals("미분류", h.get("peer_category"));
	}

	@Test
	void 팔로워_NULL_계정은_미상_버킷으로_분류된다() {
		// i는 팔로워가 NULL — '1만 미만' 버킷으로 오염되지 않고 별도 '미상'으로 빠져야 한다.
		Map<String, Object> i = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'i'");
		assertEquals("미상", i.get("follower_bucket"));
	}

	@Test
	void FnB_계정은_축_뷰에서_FnB_피어를_갖고_구_뷰에서는_미분류다() {
		// 축 뷰: k의 fnb 축 최빈은 간식류(snack 2건 > convenience 1건), beauty 축은 분류 0건 → 미분류.
		Map<String, Object> fnb = db.queryForMap(
				"SELECT * FROM account_peer_axis_stats WHERE handle = 'k' AND axis = 'fnb'");
		assertEquals("간식류", fnb.get("peer_category"));
		Map<String, Object> beauty = db.queryForMap(
				"SELECT * FROM account_peer_axis_stats WHERE handle = 'k' AND axis = 'beauty'");
		assertEquals("미분류", beauty.get("peer_category"));
		// 구 이름 뷰 = 뷰티 투영 — F&B 분류가 새어 들어오면 안 된다(기존 화면 불변).
		Map<String, Object> legacy = db.queryForMap("SELECT * FROM account_peer_stats WHERE handle = 'k'");
		assertEquals("미분류", legacy.get("peer_category"));
	}

	@Test
	void 카테고리_스탯_뷰티_축은_구_V35_정의와_동치다() {
		// 신 뷰 axis='beauty' 투영 ≡ 구 정의(is_beauty 게이트) — 양방향 EXCEPT 합 0건(스펙 §5-1).
		Long diff = db.queryForObject("""
				WITH old AS (
				  SELECT s.account_handle, COALESCE(t.main_label, a.main_category) AS main_group,
				         count(*) AS content_count
				  FROM account_content_series s
				  JOIN content_analyses a ON a.short_code = s.short_code
				  LEFT JOIN (SELECT DISTINCT main_value, main_label FROM beauty_taxonomy) t
				         ON t.main_value = a.main_category
				  WHERE a.is_beauty IS TRUE AND a.main_category IS NOT NULL
				  GROUP BY 1, 2),
				new AS (
				  SELECT account_handle, main_group, content_count
				  FROM account_category_stats WHERE axis = 'beauty')
				SELECT (SELECT count(*) FROM (SELECT * FROM old EXCEPT SELECT * FROM new) d1)
				     + (SELECT count(*) FROM (SELECT * FROM new EXCEPT SELECT * FROM old) d2)
				""", Long.class);
		assertEquals(0L, diff);
	}

	@Test
	void FnB_분류는_카테고리_스탯에_fnb_축으로_실린다() {
		Map<String, Object> row = db.queryForMap("""
				SELECT main_group, content_count FROM account_category_stats
				WHERE account_handle = 'k' AND axis = 'fnb' AND main_group = '간식류'""");
		assertEquals(2L, row.get("content_count"));
	}
}
