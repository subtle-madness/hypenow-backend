package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 해시태그 성분 저장(2026-08-27 해시태그 직접 수집 설계 §1·§2) — BrandHashtagRepositoryTest와 같은
 * Testcontainers 관용구. 겹침 병기(tagged/direct 행에 hashtag_detected_at만 얹기)·매칭 태그 누적·
 * 열거 커버 가드(hashtag-only 행 제외)를 실 컨테이너 왕복으로 고정한다.
 */
class TaggedPostHashtagSourceTest {

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	JdbcTemplate db;
	TaggedPostRepository repo;
	long brandId;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		repo = new TaggedPostRepository(db);
		brandId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('cclime_official', '99') RETURNING id",
				Long.class);
	}

	/** PostInfo 22필드 픽스처 — 이 테스트가 쓰는 값만 채우고 나머지는 null/기본이다. */
	private static PostInfo post(String code, String author, Instant takenAt) {
		return new PostInfo(code, author, null, null, "9001", "REELS", "캡션", null,
				takenAt.getEpochSecond(), 10L, 2L, 500L, null, null, null, null, null, null, null,
				true, false, false);
	}

	@Test
	void hashtag_편입은_hashtag_detected_at만_채운다() {
		repo.upsertHashtag(brandId, post("HHH", "poster1", NOW.minusSeconds(86400)), NOW);

		assertThat(db.queryForObject(
				"SELECT tag_detected_at IS NULL AND direct_registered_at IS NULL AND hashtag_detected_at IS NOT NULL"
						+ " FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'HHH'",
				Boolean.class, brandId)).isTrue();
	}

	/** 겹침 병기 — 이미 tagged로 있던 행에는 hashtag_detected_at만 얹고 tag_detected_at은 보존한다. */
	@Test
	void 기존_tagged_행에는_hashtag_성분만_병기된다() {
		repo.insert(brandId, post("BOTH", "poster1", NOW.minusSeconds(86400)));

		repo.upsertHashtag(brandId, post("BOTH", "poster1", NOW.minusSeconds(86400)), NOW);

		assertThat(db.queryForObject(
				"SELECT tag_detected_at IS NOT NULL AND hashtag_detected_at IS NOT NULL"
						+ " FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'BOTH'",
				Boolean.class, brandId)).isTrue();
		assertThat(db.queryForObject("SELECT count(*) FROM brand_tagged_post WHERE brand_id = ?",
				Integer.class, brandId)).isEqualTo(1);
	}

	/** 최초 병기 시각은 재수집으로 밀리지 않는다(COALESCE) — direct_registered_at과 같은 규칙. */
	@Test
	void 재편입은_최초_hashtag_시각을_밀지_않는다() {
		repo.upsertHashtag(brandId, post("HHH", "poster1", NOW.minusSeconds(86400)), NOW);
		repo.upsertHashtag(brandId, post("HHH", "poster1", NOW.minusSeconds(86400)), NOW.plusSeconds(86400));

		assertThat(db.queryForObject(
				"SELECT hashtag_detected_at FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'HHH'",
				java.sql.Timestamp.class, brandId).toInstant()).isEqualTo(NOW);
	}

	@Test
	void hashtagCodes는_hashtag_성분이_있는_코드만_돌려준다() {
		repo.insert(brandId, post("TAGONLY", "poster1", NOW.minusSeconds(86400)));
		repo.upsertHashtag(brandId, post("HHH", "poster2", NOW.minusSeconds(86400)), NOW);

		assertThat(repo.hashtagCodes(brandId)).containsExactly("HHH");
	}

	/** 같은 게시물이 다른 태그로 재발견되면 매칭 태그가 누적된다(멱등 upsert). */
	@Test
	void 매칭_태그는_누적되고_재기록은_멱등이다() {
		repo.upsertHashtag(brandId, post("HHH", "poster1", NOW.minusSeconds(86400)), NOW);

		repo.recordMatchedTag(brandId, "HHH", "끌리메");
		repo.recordMatchedTag(brandId, "HHH", "끌리메");
		repo.recordMatchedTags(brandId, List.of("HHH"), "cclime");

		assertThat(Set.copyOf(db.queryForList(
				"SELECT tag FROM brand_post_matched_tag WHERE brand_id = ? AND short_code = 'HHH'",
				String.class, brandId))).containsExactlyInAnyOrder("끌리메", "cclime");
	}
}
