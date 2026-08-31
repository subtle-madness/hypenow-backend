package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.time.OffsetDateTime;
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
		repo.addTags(brandId, List.of("cclime", "끌리메"));
		repo.replaceTags(brandId, List.of("cclime", "새태그"));
		assertThat(repo.findTags(brandId)).containsExactly("cclime", "새태그");
	}

	@Test
	void 삭제한_태그를_다시_replaceTags로_추가하면_재활성된다() {
		repo.addTags(brandId, List.of("cclime", "끌리메"));
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
		repo.addTags(brandId, List.of("cclime", "끌리메"));
		repo.deleteTag(brandId, "끌리메");
		assertThat(repo.findTags(brandId)).containsExactly("cclime");

		repo.addTags(brandId, List.of("끌리메"));
		assertThat(repo.findTags(brandId)).containsExactly("cclime", "끌리메");
	}

	@Test
	void 태그_단건_삭제는_멱등이다() {
		repo.addTags(brandId, List.of("cclime"));
		repo.deleteTag(brandId, "cclime");
		repo.deleteTag(brandId, "cclime");   // 없는 대상 재삭제 — 무해
		repo.deleteTag(brandId, "없는태그");   // 애초에 없던 대상 — 무해

		assertThat(repo.findTags(brandId)).isEmpty();
	}

	@Test
	void 태그_전체_삭제_후_findTags는_빈_목록이다() {
		repo.addTags(brandId, List.of("cclime", "끌리메", "cclime_official"));
		repo.deleteAllTags(brandId);

		assertThat(repo.findTags(brandId)).isEmpty();
		Long deletedCount = db.queryForObject(
				"SELECT count(*) FROM brand_hashtag WHERE brand_id = ? AND deleted_at IS NOT NULL",
				Long.class, brandId);
		assertThat(deletedCount).isEqualTo(3L);
	}

	// ---------- 매칭 태그 전체 기록(2026-08-19, was 사용자 스코프 필터 지원) ----------

	private void 게시물_저장(String shortCode) {
		repo.insertPost(new BrandHashtagRepository.HashtagPostInsert(brandId, "cclime", shortCode, "poster1",
				"포스터", "https://pic", OffsetDateTime.parse("2026-08-01T00:00:00Z"), "캡션", "REELS",
				"https://thumb", 10L, 2L, "RELEVANT", "LLM"));
	}

	@Test
	void recordTagMatch은_여러_태그를_같은_게시물에_누적한다() {
		게시물_저장("AAA");

		repo.recordTagMatch(brandId, "AAA", "cclime");
		repo.recordTagMatch(brandId, "AAA", "끌리메");

		List<String> tags = db.queryForList(
				"SELECT tag FROM brand_hashtag_post_matched_tags WHERE brand_id = ? AND short_code = ? ORDER BY tag",
				String.class, brandId, "AAA");
		assertThat(tags).containsExactly("cclime", "끌리메");
	}

	@Test
	void recordTagMatch은_같은_조합_재기록에_멱등이다() {
		게시물_저장("AAA");

		repo.recordTagMatch(brandId, "AAA", "cclime");
		repo.recordTagMatch(brandId, "AAA", "cclime");

		Long count = db.queryForObject(
				"SELECT count(*) FROM brand_hashtag_post_matched_tags WHERE brand_id = ? AND short_code = ?",
				Long.class, brandId, "AAA");
		assertThat(count).isEqualTo(1L);
	}

	@Test
	void recordTagMatches는_배치로_기록한다() {
		게시물_저장("AAA");
		게시물_저장("BBB");

		repo.recordTagMatches(brandId, List.of("AAA", "BBB"), "끌리메");

		List<String> codes = db.queryForList(
				"SELECT short_code FROM brand_hashtag_post_matched_tags WHERE brand_id = ? AND tag = ? ORDER BY short_code",
				String.class, brandId, "끌리메");
		assertThat(codes).containsExactly("AAA", "BBB");
	}

	// ---------- 태그별 스윕 실행 상태(FE 요청, 2026-08-31) ----------

	@Test
	void 신규_태그의_실행_상태는_전부_비어있다() {
		repo.addTags(brandId, List.of("cclime"));

		List<BrandHashtagRepository.RunStateRow> states = repo.findRunStates(brandId);

		assertThat(states).hasSize(1);
		BrandHashtagRepository.RunStateRow row = states.get(0);
		assertThat(row.tag()).isEqualTo("cclime");
		assertThat(row.lastRunStartedAt()).isNull();
		assertThat(row.lastRunFinishedAt()).isNull();
		assertThat(row.lastRunFoundCount()).isNull();
		assertThat(row.lastRunFailed()).isFalse();
	}

	@Test
	void markRunStarted는_시작_시각만_채운다() {
		repo.addTags(brandId, List.of("cclime"));

		repo.markRunStarted(brandId, "cclime");

		BrandHashtagRepository.RunStateRow row = repo.findRunStates(brandId).get(0);
		assertThat(row.lastRunStartedAt()).isNotNull();
		assertThat(row.lastRunFinishedAt()).isNull();
	}

	@Test
	void markRunFinished는_종료_시각_건수_실패_여부를_채운다() {
		repo.addTags(brandId, List.of("cclime"));
		repo.markRunStarted(brandId, "cclime");

		repo.markRunFinished(brandId, "cclime", 4, false);

		BrandHashtagRepository.RunStateRow row = repo.findRunStates(brandId).get(0);
		assertThat(row.lastRunFinishedAt()).isNotNull();
		assertThat(row.lastRunFoundCount()).isEqualTo(4);
		assertThat(row.lastRunFailed()).isFalse();
	}

	@Test
	void markRunFinished_실패_기록은_failed를_true로_남긴다() {
		repo.addTags(brandId, List.of("cclime"));
		repo.markRunStarted(brandId, "cclime");

		repo.markRunFinished(brandId, "cclime", 0, true);

		BrandHashtagRepository.RunStateRow row = repo.findRunStates(brandId).get(0);
		assertThat(row.lastRunFailed()).isTrue();
		assertThat(row.lastRunFoundCount()).isEqualTo(0);
	}

	/** deleted_at 있는(tombstone) 태그는 findRunStates에서 빠진다 — findTags와 같은 필터. */
	@Test
	void 삭제된_태그는_실행_상태_조회에서_빠진다() {
		repo.addTags(brandId, List.of("cclime", "끌리메"));
		repo.markRunStarted(brandId, "cclime");
		repo.markRunStarted(brandId, "끌리메");
		repo.deleteTag(brandId, "끌리메");

		List<BrandHashtagRepository.RunStateRow> states = repo.findRunStates(brandId);

		assertThat(states).extracting(BrandHashtagRepository.RunStateRow::tag).containsExactly("cclime");
	}

}
