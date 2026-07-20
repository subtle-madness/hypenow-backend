package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.testsupport.TestDb;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V30 시드 ↔ celfit-front 배포본(2026-07-14) 필터 어휘 계약 검증.
 * was는 verbatim 매칭만 하므로(§4-4) 이 시드가 곧 목록 API 필터의 어휘다.
 */
@Testcontainers
class BeautyTaxonomySeedTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static JdbcTemplate db;
	static BeautyTaxonomy taxonomy;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		taxonomy = new BeautyTaxonomyLoader(ds).get();
	}

	@Test
	void 대분류_slug는_프론트_배포본_6종이다() {
		assertEquals(Set.of("skincare", "suncare", "makeup", "cleansing", "haircare", "fragrance"),
				taxonomy.mainCategories());
	}

	@Test
	void 중분류와_소분류_라벨을_모두_포함하는_집합을_제공한다() {
		Set<String> labels = taxonomy.allMidAndSubLabels();

		// 프론트 mid/sub 필터가 sub_categories 배열 포함 여부로 매칭 — 중분류·소분류 라벨 둘 다 어휘다
		assertTrue(labels.contains("립메이크업")); // 중분류
		assertTrue(labels.contains("립틴트"));     // 소분류
		assertTrue(labels.contains("홈프래그런스"));
		assertTrue(labels.contains("차량용방향제"));
	}

	@Test
	void 소분류_라벨_집합은_중분류를_포함하지_않는다() {
		Set<String> subs = taxonomy.allSubLabels();

		assertTrue(subs.contains("립틴트"));
		assertTrue(subs.contains("아이래쉬 케어"));
		assertFalse(subs.contains("립메이크업")); // 중분류는 카드 칩(제품 카테고리) 어휘가 아니다
	}

	@Test
	void 시드는_소분류_72행이다() {
		// 시드 행 누락·중복을 총량으로 방어 (프론트 배포본 소분류 수 — Set은 동명 라벨이 접혀 SQL로 센다)
		assertEquals(72L, db.queryForObject("SELECT count(*) FROM beauty_taxonomy", Long.class));
	}

	@Test
	void 유통사_어휘는_프론트_필터값_고정이다() {
		assertEquals(Set.of("올리브영", "다이소"), taxonomy.distributors());
		assertEquals("올리브영|다이소", taxonomy.distributorsPrompt());
	}

	@Test
	void 프롬프트_분류표는_slug와_라벨_계층을_담는다() {
		String table = taxonomy.promptTable();

		assertTrue(table.contains("skincare(스킨케어)"));
		assertTrue(table.contains("fragrance(향수/디퓨저)"));
		assertTrue(table.contains("립메이크업"));
		assertTrue(table.contains("립틴트"));
	}
}
