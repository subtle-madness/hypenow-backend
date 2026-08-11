package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 해시태그 감지 저장(스펙 2026-08-11) — BrandStoreTest와 같은 Testcontainers 관용구.
 * 기존 브랜드 테이블(brand_tag_monitoring 계열)은 여기서 건드리지 않는다.
 */
class BrandHashtagRepositoryTest {

	JdbcTemplate db;
	BrandHashtagRepository repo;
	long brandId;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		repo = new BrandHashtagRepository(db);
		brandId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('cclime_official', '99') RETURNING id",
				Long.class);
	}

	@Test
	void 태그_삽입은_멱등이다() {
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("끌리메", "cclime")));
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("cclime", "cclime_official")));
		assertThat(repo.findTags(brandId)).containsExactly("끌리메", "cclime", "cclime_official");
	}

	@Test
	void 제외_문자열은_기본값_삽입_후_전체_교체가_가능하다() {
		repo.insertDefaultExclusion(brandId, "cclime");
		repo.insertDefaultExclusion(brandId, "cclime");   // 멱등
		assertThat(repo.findExclusionTerms(brandId)).containsExactly("cclime");
		repo.replaceExclusionTerms(brandId, List.of("cclime", "cclimebeauty"));
		assertThat(repo.findExclusionTerms(brandId)).containsExactly("cclime", "cclimebeauty");
		repo.replaceExclusionTerms(brandId, List.of());
		assertThat(repo.findExclusionTerms(brandId)).isEmpty();
	}

	@Test
	void 게시물_저장과_기존_코드_조회가_동작한다() {
		repo.insertPost(brandId, "끌리메", "AAA", "poster1", "포스터", "https://pic",
				OffsetDateTime.parse("2026-08-01T00:00:00Z"), "캡션", "REELS", "https://thumb",
				10L, 2L, "RELEVANT", "LLM");
		// 같은 (brand, code) 재삽입은 무시(ON CONFLICT DO NOTHING)
		repo.insertPost(brandId, "cclime", "AAA", "poster1", null, null,
				OffsetDateTime.parse("2026-08-01T00:00:00Z"), "다른캡션", null, null,
				null, null, "IRRELEVANT", "LLM");
		Set<String> existing = repo.existingCodes(brandId, List.of("AAA", "BBB"));
		assertThat(existing).containsExactly("AAA");
		assertThat(db.queryForObject(
				"SELECT verdict FROM brand_hashtag_post WHERE brand_id = ? AND short_code = 'AAA'",
				String.class, brandId)).isEqualTo("RELEVANT");
	}

	@Test
	void 빈_코드_목록은_빈_집합을_돌려준다() {
		assertThat(repo.existingCodes(brandId, List.of())).isEmpty();
	}
}
