package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.testsupport.TestDb;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V41 시드 ↔ 스펙 부록 A(2026-07-29, 사용자 확정 172개·13축) 계약 검증.
 * V43(07-30) '메이크업 리뷰' 추가분 포함 — 총 173개·13축(축 개수는 불변, B. 리뷰 방식만 +1).
 */
@Testcontainers
class TraitTaxonomySeedTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static JdbcTemplate db;
	static TraitTaxonomy taxonomy;

	@BeforeAll
	static void migrate() {
		DataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		taxonomy = new TraitTaxonomyLoader(ds).get();
	}

	@Test
	void 시드는_173개_13축이다() {
		assertEquals(173L, db.queryForObject("SELECT count(*) FROM trait_taxonomy", Long.class));
		assertEquals(13L, db.queryForObject("SELECT count(DISTINCT facet) FROM trait_taxonomy", Long.class));
	}

	@Test
	void 캐노니컬_집합_스팟체크() {
		assertTrue(taxonomy.names().contains("솔직 리뷰"));
		assertTrue(taxonomy.names().contains("릴스 중심"));
		assertTrue(taxonomy.names().contains("여름쿨톤"));
		assertTrue(taxonomy.names().contains("무쌍 메이크업"));
		assertTrue(taxonomy.names().contains("50대 이상"));
	}

	@Test
	void 프롬프트_블록은_축별_구획으로_전_어휘를_담는다() {
		String block = taxonomy.promptBlock();
		assertTrue(block.contains("[콘텐츠 형식]"));
		assertTrue(block.contains("[퍼스널컬러]"));
		assertTrue(block.contains("솔직 리뷰"));
		assertTrue(block.contains("키작녀 코디"));
	}

	@Test
	void canon_log_테이블이_존재한다() {
		db.update("INSERT INTO trait_canon_log (raw_value, canon_value, mapped_at) VALUES ('x','솔직 리뷰', now())");
		assertEquals(1L, db.queryForObject("SELECT count(*) FROM trait_canon_log WHERE raw_value='x'", Long.class));
	}
}
