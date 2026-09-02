package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.testsupport.TestDb;
import java.util.List;
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
	void 뷰티축_대분류_slug는_프론트_배포본_7종이다() {
		// 2026-08-31 F&B 어휘 추가로 mainCategories()는 축을 섞는다 — 프론트 계약(뷰티 필터)은
		// 축으로 좁혀 검증한다. 축 전체 목록은 아래 F&B 테스트가 함께 본다.
		assertEquals(Set.of("skincare", "suncare", "makeup", "cleansing", "haircare", "fragrance", "esthetic"),
				Set.copyOf(db.queryForList(
						"SELECT DISTINCT main_value FROM beauty_taxonomy WHERE axis = 'beauty'",
						String.class)));
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
	void 시드는_축별_소분류_행수가_고정이다() {
		// 시드 행 누락·중복을 총량으로 방어 (Set은 동명 라벨이 접혀 SQL로 센다).
		// 축을 나눠 세는 이유: 한쪽 어휘를 늘려도 다른 축의 회귀는 그대로 잡힌다.
		assertEquals(86L, db.queryForObject(
				"SELECT count(*) FROM beauty_taxonomy WHERE axis = 'beauty'", Long.class));
		assertEquals(24L, db.queryForObject(
				"SELECT count(*) FROM beauty_taxonomy WHERE axis = 'fnb'", Long.class));
	}

	@Test
	void 유통사_어휘는_축별_프론트_필터값_고정이다() {
		assertEquals(Set.of("올리브영", "다이소"), Set.copyOf(db.queryForList(
				"SELECT name FROM beauty_distributors WHERE axis = 'beauty'", String.class)));
	}

	@Test
	void FnB_대분류_6개가_fnb축으로_시드된다() {
		assertEquals(List.of("alcohol", "beverage", "convenience", "health-food", "recipe", "snack"),
				db.queryForList(
						"SELECT DISTINCT main_value FROM beauty_taxonomy WHERE axis = 'fnb' ORDER BY 1",
						String.class));
		// 기존 뷰티 어휘는 전부 beauty 축으로 남는다 (DEFAULT 백필)
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM beauty_taxonomy WHERE axis NOT IN ('beauty','fnb')", Long.class));
	}

	@Test
	void 소분류_라벨은_축_전체에서_유일하다() {
		// sub_categories는 정확 일치 매칭이라 라벨이 여러 대분류에 걸치면 필터가 오탐한다.
		// 요리/레시피의 '음료'를 '음료 레시피'로 분리한 이유 (설계 §3).
		assertEquals(List.of(), db.queryForList("""
				SELECT sub_label FROM beauty_taxonomy
				GROUP BY sub_label HAVING count(DISTINCT main_value) > 1
				ORDER BY 1""", String.class), "소분류 라벨이 여러 대분류에 걸침 — 필터 오탐");
	}

	@Test
	void FnB_유통사가_fnb축으로_시드된다() {
		assertEquals(11L, db.queryForObject(
				"SELECT count(*) FROM beauty_distributors WHERE axis = 'fnb'", Long.class));
		// slug는 was distributorId 필터값 — NOT NULL·UNIQUE 계약을 F&B 행도 지켜야 한다
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM beauty_distributors WHERE slug IS NULL", Long.class));
	}

	@Test
	void 프롬프트_분류표는_slug와_라벨_계층을_담는다() {
		String table = taxonomy.promptTable();

		assertTrue(table.contains("skincare(스킨케어)"));
		assertTrue(table.contains("fragrance(향수/디퓨저)"));
		assertTrue(table.contains("립메이크업"));
		assertTrue(table.contains("립틴트"));
	}

	@Test
	void 에스테틱_어휘가_시드된다() {
		// 2026-08-09 스펙 §3 — 디바이스·툴·피부 시술 14개. 라벨은 프론트 계약이라 표기 그대로 검증.
		assertTrue(taxonomy.mainCategories().contains("esthetic"));

		Set<String> labels = taxonomy.allMidAndSubLabels();
		for (String label : List.of("뷰티 디바이스", "뷰티 툴", "피부 시술·관리",
				"LED 마스크", "미세전류 기기", "고주파 기기", "클렌징 기기", "제모 기기",
				"괄사", "페이스 롤러", "마사지 도구",
				"에스테틱 관리", "경락 마사지", "피부과 레이저", "스킨부스터", "리프팅 시술", "필링 시술")) {
			assertTrue(labels.contains(label), label);
		}

		// 소분류 칩 어휘: 소분류만 포함, 중분류 미포함
		assertTrue(taxonomy.allSubLabels().contains("스킨부스터"));
		assertFalse(taxonomy.allSubLabels().contains("피부 시술·관리"));

		// 역유도: 에스테틱 라벨만 있으면 esthetic으로 복구된다 (sanitize 경로)
		assertEquals("esthetic", taxonomy.deriveMain(List.of("경락 마사지", "괄사")));
	}
}
