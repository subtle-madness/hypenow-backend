package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.celfit.analytics.testsupport.TestDb;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 계정 카테고리 믹스 파생 뷰(V35) — 미러가 아니라 analysis DB 안에서 계산되므로
 * raw SQL 하니스(analytics/test/*.test.sql)가 아닌 Flyway 컨테이너로 검증한다.
 */
class AccountCategoryStatsViewTest {

	static final PostgreSQLContainer pg = TestDb.shared();

	static JdbcTemplate db;

	@BeforeAll
	static void seed() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		// 공유 컨테이너라 이전 클래스의 잔재 위에서 migrate하면 안 된다 — 리셋 후 전체 재생
		TestDb.resetAndMigrate(db, ds);
		// 최근창(account_content_series) 5건: a는 makeup 2 + skincare 1, b는 makeup 1.
		// a4는 비뷰티(F&B 대분류 snack, is_beauty=false)라 fnb 축으로 빠지고, a5는 대분류 NULL이라
		// 축과 무관하게 믹스에서 빠진다 — 2026-09-01 축 인지화 이후 "뷰티 믹스"는 axis='beauty' 투영.
		// (뷰 게이트가 is_beauty에서 어휘 축으로 바뀌었으므로 시드도 불변식
		//  `main NOT NULL ∧ axis=beauty ⟺ is_beauty=true ∧ main NOT NULL`을 지키는 값으로 둔다.)
		db.update("""
				INSERT INTO account_content_series
				  (short_code, account_handle, posted_at, content_type, views, likes, comments, sponsored)
				VALUES ('a1','acc_a',now(),'reels',100,10,1,false),
				       ('a2','acc_a',now(),'feed',NULL,20,2,false),
				       ('a3','acc_a',now(),'reels',300,30,3,false),
				       ('a4','acc_a',now(),'reels',400,40,4,false),
				       ('a5','acc_a',now(),'reels',500,50,5,false),
				       ('b1','acc_b',now(),'reels',600,60,6,false),
				       ('x1','acc_x',now(),'reels',700,70,7,false)""");
		db.update("""
				INSERT INTO content_analyses (short_code, model, main_category, is_beauty)
				VALUES ('a1','t','makeup',true),
				       ('a2','t','makeup',true),
				       ('a3','t','skincare',true),
				       ('a4','t','snack',false),
				       ('a5','t',NULL,true),
				       ('b1','t','makeup',true)""");
		// x1은 분석 자체가 없다 — 미분석 게시물은 믹스에 안 잡힌다.
	}

	@Test
	void 대분류_한글라벨로_최근창_건수를_집계한다() {
		List<Map<String, Object>> rows = db.queryForList("""
				SELECT main_group, content_count FROM account_category_stats
				WHERE account_handle = 'acc_a' AND axis = 'beauty'
				ORDER BY content_count DESC, main_group""");

		assertEquals(2, rows.size());
		assertEquals("메이크업", rows.get(0).get("main_group"));
		assertEquals(2L, rows.get(0).get("content_count"));
		assertEquals("스킨케어", rows.get(1).get("main_group"));
		assertEquals(1L, rows.get(1).get("content_count"));
	}

	@Test
	void 비뷰티_무대분류_미분석은_제외된다() {
		// 비뷰티(a4)는 뷰티 축 투영에서 빠지고 fnb 축으로 간다. 무대분류(a5)·미분석(x1)은 양축 모두 없다.
		assertEquals(3L, db.queryForObject("""
				SELECT sum(content_count) FROM account_category_stats
				WHERE account_handle = 'acc_a' AND axis = 'beauty'""", Long.class));
		assertEquals(1L, db.queryForObject("""
				SELECT sum(content_count) FROM account_category_stats
				WHERE account_handle = 'acc_a' AND axis = 'fnb'""", Long.class));
		assertEquals(0, db.queryForObject(
				"SELECT count(*) FROM account_category_stats WHERE account_handle = 'acc_x'",
				Integer.class));
	}

	@Test
	void 계정별로_행이_분리된다() {
		assertEquals(1L, db.queryForObject(
				"SELECT content_count FROM account_category_stats WHERE account_handle = 'acc_b'",
				Long.class));
	}
}
