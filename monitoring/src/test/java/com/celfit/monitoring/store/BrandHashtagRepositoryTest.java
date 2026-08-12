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
		repo.insertPost(new BrandHashtagRepository.HashtagPostInsert(brandId, "끌리메", "AAA", "poster1",
				"포스터", "https://pic", OffsetDateTime.parse("2026-08-01T00:00:00Z"), "캡션", "REELS",
				"https://thumb", 10L, 2L, "RELEVANT", "LLM"));
		// 같은 (brand, code) 재삽입은 무시(ON CONFLICT DO NOTHING)
		repo.insertPost(new BrandHashtagRepository.HashtagPostInsert(brandId, "cclime", "AAA", "poster1",
				null, null, OffsetDateTime.parse("2026-08-01T00:00:00Z"), "다른캡션", null, null,
				null, null, "IRRELEVANT", "LLM"));
		Set<String> existing = repo.existingCodes(brandId, List.of("AAA", "BBB"));
		assertThat(existing).containsExactly("AAA");
		assertThat(db.queryForObject(
				"SELECT verdict FROM brand_hashtag_post WHERE brand_id = ? AND short_code = 'AAA'",
				String.class, brandId)).isEqualTo("RELEVANT");
		// 재삽입 무시는 verdict뿐 아니라 원본 필드 전체가 살아남는다 — author_full_name으로 대표 확인
		assertThat(db.queryForObject(
				"SELECT author_full_name FROM brand_hashtag_post WHERE brand_id = ? AND short_code = 'AAA'",
				String.class, brandId)).isEqualTo("포스터");
	}

	@Test
	void 빈_코드_목록은_빈_집합을_돌려준다() {
		assertThat(repo.existingCodes(brandId, List.of())).isEmpty();
	}

	@Test
	void 태그_전체_교체는_findTags에_반영된다() {
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("cclime", "끌리메")));
		repo.replaceTags(brandId, List.of("cclime", "새태그"));
		assertThat(repo.findTags(brandId)).containsExactly("cclime", "새태그");
	}

	/**
	 * tombstone 의미론의 핵심 — 유저가 지운 태그는 deleted_at이 채워진 행으로 남고, 등록 replay가
	 * 부르는 insertTags(ON CONFLICT DO NOTHING)로는 절대 되살아나지 않는다(유저 삭제 의사 보존).
	 */
	@Test
	void 삭제된_태그는_insertTags_시드로_부활하지_않는다() {
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("cclime", "끌리메")));
		repo.replaceTags(brandId, List.of("cclime"));   // "끌리메" 삭제(tombstone)
		assertThat(repo.findTags(brandId)).containsExactly("cclime");

		// 등록 replay가 유니온 시드를 재삽입해도 tombstone 행은 ON CONFLICT DO NOTHING에 막힌다.
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("cclime", "끌리메", "cclime_official")));
		assertThat(repo.findTags(brandId)).containsExactly("cclime", "cclime_official");

		// 행 자체는 deleted_at을 채운 채 남아 있다(하드 삭제가 아님).
		Long deletedCount = db.queryForObject(
				"SELECT count(*) FROM brand_hashtag WHERE brand_id = ? AND tag = ? AND deleted_at IS NOT NULL",
				Long.class, brandId, "끌리메");
		assertThat(deletedCount).isEqualTo(1L);
	}

	@Test
	void 삭제한_태그를_다시_replaceTags로_추가하면_재활성된다() {
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("cclime", "끌리메")));
		repo.replaceTags(brandId, List.of("cclime"));   // "끌리메" 삭제
		repo.replaceTags(brandId, List.of("cclime", "끌리메"));   // 재추가 — tombstone 해제

		assertThat(repo.findTags(brandId)).containsExactly("cclime", "끌리메");
		Long deletedCount = db.queryForObject(
				"SELECT count(*) FROM brand_hashtag WHERE brand_id = ? AND tag = ? AND deleted_at IS NOT NULL",
				Long.class, brandId, "끌리메");
		assertThat(deletedCount).isEqualTo(0L);
	}

	// ---------- 태그 단건 추가·삭제·전체 삭제(2026-08-12, 표준 REST 확장) ----------

	/** addTags(POST)의 tombstone 재활성이 replaceTags와 동일 UPSERT 구문을 쓰는지 확인. */
	@Test
	void 태그_추가는_tombstone을_되살린다() {
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("cclime", "끌리메")));
		repo.deleteTag(brandId, "끌리메");
		assertThat(repo.findTags(brandId)).containsExactly("cclime");

		repo.addTags(brandId, List.of("끌리메"));
		assertThat(repo.findTags(brandId)).containsExactly("cclime", "끌리메");
	}

	@Test
	void 태그_단건_삭제는_멱등이다() {
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("cclime")));
		repo.deleteTag(brandId, "cclime");
		repo.deleteTag(brandId, "cclime");   // 없는 대상 재삭제 — 무해
		repo.deleteTag(brandId, "없는태그");   // 애초에 없던 대상 — 무해

		assertThat(repo.findTags(brandId)).isEmpty();
	}

	@Test
	void 태그_전체_삭제_후_findTags는_빈_목록이다() {
		repo.insertTags(brandId, new LinkedHashSet<>(List.of("cclime", "끌리메", "cclime_official")));
		repo.deleteAllTags(brandId);

		assertThat(repo.findTags(brandId)).isEmpty();
		Long deletedCount = db.queryForObject(
				"SELECT count(*) FROM brand_hashtag WHERE brand_id = ? AND deleted_at IS NOT NULL",
				Long.class, brandId);
		assertThat(deletedCount).isEqualTo(3L);
	}

	// ---------- 제외 문자열 tombstone·단건 추가·삭제·전체 삭제(2026-08-12) ----------

	/**
	 * tombstone 의미론의 핵심(제외 문자열 판) — 유저가 지운 문자열은 deleted_at이 채워진 행으로
	 * 남고, 등록 replay가 부르는 insertDefaultExclusion(ON CONFLICT DO NOTHING)로는 되살아나지 않는다.
	 */
	@Test
	void 삭제된_제외_문자열은_insertDefaultExclusion_시드로_부활하지_않는다() {
		repo.insertDefaultExclusion(brandId, "cclime");
		repo.deleteExclusionTerm(brandId, "cclime");
		assertThat(repo.findExclusionTerms(brandId)).isEmpty();

		// 등록 replay가 기본값 시드를 재시도해도 tombstone 행은 ON CONFLICT DO NOTHING에 막힌다.
		repo.insertDefaultExclusion(brandId, "cclime");
		assertThat(repo.findExclusionTerms(brandId)).isEmpty();

		Long deletedCount = db.queryForObject(
				"SELECT count(*) FROM brand_hashtag_exclusion WHERE brand_id = ? AND term = ? "
						+ "AND deleted_at IS NOT NULL",
				Long.class, brandId, "cclime");
		assertThat(deletedCount).isEqualTo(1L);
	}

	@Test
	void 제외_문자열_추가는_tombstone을_되살린다() {
		repo.insertDefaultExclusion(brandId, "cclime");
		repo.deleteExclusionTerm(brandId, "cclime");
		assertThat(repo.findExclusionTerms(brandId)).isEmpty();

		repo.addExclusionTerms(brandId, List.of("cclime", "cclimebeauty"));
		assertThat(repo.findExclusionTerms(brandId)).containsExactly("cclime", "cclimebeauty");
	}

	@Test
	void 제외_문자열_단건_삭제는_멱등이다() {
		repo.insertDefaultExclusion(brandId, "cclime");
		repo.deleteExclusionTerm(brandId, "cclime");
		repo.deleteExclusionTerm(brandId, "cclime");   // 재삭제 — 무해
		repo.deleteExclusionTerm(brandId, "없는문자열");   // 애초에 없던 대상 — 무해

		assertThat(repo.findExclusionTerms(brandId)).isEmpty();
	}

	@Test
	void 제외_문자열_전체_삭제_후_findExclusionTerms는_빈_목록이다() {
		repo.replaceExclusionTerms(brandId, List.of("cclime", "cclimebeauty"));
		repo.deleteAllExclusionTerms(brandId);

		assertThat(repo.findExclusionTerms(brandId)).isEmpty();
		Long deletedCount = db.queryForObject(
				"SELECT count(*) FROM brand_hashtag_exclusion WHERE brand_id = ? AND deleted_at IS NOT NULL",
				Long.class, brandId);
		assertThat(deletedCount).isEqualTo(2L);
	}

	@Test
	void 제외_문자열_삭제한_뒤_replaceExclusionTerms로_재추가하면_재활성된다() {
		repo.insertDefaultExclusion(brandId, "cclime");
		repo.deleteExclusionTerm(brandId, "cclime");
		repo.replaceExclusionTerms(brandId, List.of("cclime"));   // 재추가 — tombstone 해제

		assertThat(repo.findExclusionTerms(brandId)).containsExactly("cclime");
		Long deletedCount = db.queryForObject(
				"SELECT count(*) FROM brand_hashtag_exclusion WHERE brand_id = ? AND term = ? "
						+ "AND deleted_at IS NOT NULL",
				Long.class, brandId, "cclime");
		assertThat(deletedCount).isEqualTo(0L);
	}
}
