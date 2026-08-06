package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.AuthorInfo;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 브랜드 태그 모니터링 스토어 3종(2026-08-06 스펙) — StoreTest와 같은 Testcontainers 관용구. */
class BrandStoreTest {

	private JdbcTemplate db;
	private BrandRepository brands;
	private TaggedPostRepository taggedPosts;
	private AuthorProfileRepository authors;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		brands = new BrandRepository(db);
		taggedPosts = new TaggedPostRepository(db);
		authors = new AuthorProfileRepository(db);
	}

	@Test
	void 브랜드_등록과_재가입_재활성() {
		long id = brands.insertOrReactivate("brandx", "111", 1000L, "소개");
		assertThat(brands.findActive()).hasSize(1);
		assertThat(brands.close("brandx")).isTrue();
		assertThat(brands.findActive()).isEmpty();
		assertThat(brands.close("brandx")).isFalse();          // 이미 닫힘 — 멱등
		long reId = brands.insertOrReactivate("brandx", "111", 2000L, "소개2");
		assertThat(reId).isEqualTo(id);                        // 같은 행 재활성(UNIQUE username)
		BrandRow row = brands.findByUsername("brandx").orElseThrow();
		assertThat(row.status()).isEqualTo(BrandStatus.ACTIVE);
		assertThat(row.lastTrackedOn()).isNull();              // 재가입 시 초기화 — 백필 백스톱 재발동
	}

	@Test
	void 트래킹_일자_갱신() {
		long id = brands.insertOrReactivate("brandx", "111", null, null);
		brands.touchTracked(id, LocalDate.of(2026, 8, 6));
		assertThat(brands.findByUsername("brandx").orElseThrow().lastTrackedOn())
				.isEqualTo(LocalDate.of(2026, 8, 6));
	}

	@Test
	void 태그_게시물_링크와_댓글_게이트_상태() {
		long id = brands.insertOrReactivate("brandx", "111", null, null);
		taggedPosts.insert(id, post("CodeA", 1754000000L));
		taggedPosts.insert(id, post("CodeA", 1754000000L));    // 재감지 — ON CONFLICT 무해
		assertThat(taggedPosts.knownCodes(id)).containsExactly("CodeA");
		assertThat(taggedPosts.commentsCollectedCounts(id, Set.of("CodeA")))
				.containsEntry("CodeA", 0L);
		taggedPosts.updateCommentsCollected(id, "CodeA", 7);
		assertThat(taggedPosts.commentsCollectedCounts(id, Set.of("CodeA")))
				.containsEntry("CodeA", 7L);
		assertThat(taggedPosts.commentsCollectedCounts(id, List.of())).isEmpty();
	}

	@Test
	void 게시자_캐시_upsert와_stale_판정() {
		authors.upsert(new AuthorInfo("999", "creator", "이름", 100L, 10L, 5L, "bio", "https://p", false));
		// 방금 넣은 행은 신선하다 — 30일 전 기준으로 fresh 집합에 있어야 한다. 888은 미보유 → 콜 필요.
		assertThat(authors.freshIgUserIds(Set.of("999", "888"),
				Instant.now().minusSeconds(30L * 24 * 3600)))
				.containsExactly("999");
		// 기준이 미래면 전원 stale — 콜 필요.
		assertThat(authors.freshIgUserIds(Set.of("999"), Instant.now().plusSeconds(60))).isEmpty();
		assertThat(authors.freshIgUserIds(List.of(), Instant.now())).isEmpty();
		// 재조회 upsert가 관측값을 덮는다(이력 없이 최신 1행).
		authors.upsert(new AuthorInfo("999", "creator", "이름", 200L, 10L, 5L, "bio2", "https://p", true));
		assertThat(db.queryForObject(
				"SELECT followers FROM author_profile WHERE ig_user_id='999'", Long.class)).isEqualTo(200L);
		assertThat(db.queryForObject("SELECT count(*) FROM author_profile", Long.class)).isEqualTo(1L);
	}

	private static PostInfo post(String code, long takenAt) {
		return new PostInfo(code, "creator", null, null, "999", "REELS", "캡션", null,
				takenAt, 10L, 2L, 100L, null, null, null, null, "{}", true, false, false);
	}
}
