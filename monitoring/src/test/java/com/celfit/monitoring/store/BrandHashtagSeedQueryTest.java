package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.instagram.source.PostInfo;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 해시태그 제안 계산 입력 2종(2026-09-03 자동 시드 재설계 §3-2·§3-3) — BrandHashtagRepositoryTest와
 * 같은 Testcontainers 관용구. "tag 성분 게시물의 캡션"과 IG 표시명 조회를 실 컨테이너 왕복으로 고정한다.
 */
class BrandHashtagSeedQueryTest {

	private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

	JdbcTemplate db;
	TaggedPostRepository taggedPosts;
	BrandPostMetaRepository meta;
	BrandRepository brands;
	long brandId;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		taggedPosts = new TaggedPostRepository(db);
		meta = new BrandPostMetaRepository(db);
		brands = new BrandRepository(db);
		brandId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('cclime_official', '99') RETURNING id",
				Long.class);
	}

	private static PostInfo post(String code, Instant takenAt) {
		return new PostInfo(code, "poster1", null, null, "9001", "REELS", "캡션", null,
				takenAt.getEpochSecond(), 10L, 2L, 500L, null, null, null, null, null, null, null,
				true, false, false);
	}

	private void writeMeta(String code, String caption) {
		meta.upsert(code, "poster1", "REELS", LocalDate.of(2026, 9, 1), caption,
				"https://thumb", null, null, null);
	}

	// ---------- findCaptionsForSeed (FREQ 모수) ----------

	@Test
	void tag_성분_게시물의_캡션과_게시일을_돌려준다() {
		taggedPosts.insert(brandId, post("AAA", NOW.minusSeconds(86400)));
		writeMeta("AAA", "오늘 #끌리메");

		List<TaggedPostRepository.TaggedCaption> out = taggedPosts.findCaptionsForSeed(brandId);

		assertThat(out).singleElement().satisfies(row -> {
			assertThat(row.caption()).isEqualTo("오늘 #끌리메");
			assertThat(row.takenAt()).isEqualTo(NOW.minusSeconds(86400));
		});
	}

	/** hashtag-only 행은 모수에서 빠진다 — 구 절삭 태그로 긁힌 무관 게시물 오염 차단(§3-2). */
	@Test
	void hashtag_성분만_있는_게시물은_제외된다() {
		taggedPosts.upsertHashtag(brandId, post("HHH", NOW.minusSeconds(86400)), NOW);
		writeMeta("HHH", "무관 게시물 #dr");

		assertThat(taggedPosts.findCaptionsForSeed(brandId)).isEmpty();
	}

	/** 겹침 행(tag + hashtag)은 tag 성분이 있으므로 포함된다. */
	@Test
	void tag_성분이_있으면_hashtag_겹침_행도_포함된다() {
		taggedPosts.insert(brandId, post("BOTH", NOW.minusSeconds(86400)));
		taggedPosts.upsertHashtag(brandId, post("BOTH", NOW.minusSeconds(86400)), NOW);
		writeMeta("BOTH", "#끌리메");

		assertThat(taggedPosts.findCaptionsForSeed(brandId)).hasSize(1);
	}

	@Test
	void 메타가_없는_게시물은_제외된다() {
		taggedPosts.insert(brandId, post("NOMETA", NOW.minusSeconds(86400)));

		assertThat(taggedPosts.findCaptionsForSeed(brandId)).isEmpty();
	}

	@Test
	void 캡션이_비었거나_null이면_제외된다() {
		taggedPosts.insert(brandId, post("EMPTY", NOW.minusSeconds(86400)));
		writeMeta("EMPTY", "");
		taggedPosts.insert(brandId, post("NULLCAP", NOW.minusSeconds(86400)));
		writeMeta("NULLCAP", null);

		assertThat(taggedPosts.findCaptionsForSeed(brandId)).isEmpty();
	}

	@Test
	void 다른_브랜드의_게시물은_제외된다() {
		long otherId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('other', '98') RETURNING id",
				Long.class);
		taggedPosts.insert(otherId, post("OTHER", NOW.minusSeconds(86400)));
		writeMeta("OTHER", "#남의태그");

		assertThat(taggedPosts.findCaptionsForSeed(brandId)).isEmpty();
	}

	// ---------- findFullName (AI 입력) ----------

	@Test
	void 표시명이_있으면_돌려준다() {
		db.update("UPDATE brand_account SET full_name = ? WHERE id = ?", "닥터피엘 Dr.PIEL", brandId);

		assertThat(brands.findFullName(brandId)).contains("닥터피엘 Dr.PIEL");
	}

	@Test
	void 표시명이_null이면_empty다() {
		assertThat(brands.findFullName(brandId)).isEmpty();
	}

	@Test
	void 표시명이_공백뿐이면_empty다() {
		db.update("UPDATE brand_account SET full_name = ? WHERE id = ?", "   ", brandId);

		assertThat(brands.findFullName(brandId)).isEmpty();
	}

	@Test
	void 없는_브랜드는_empty다() {
		assertThat(brands.findFullName(-1L)).isEmpty();
	}
}
