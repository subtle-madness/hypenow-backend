package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.AuthorInfo;
import com.celfit.monitoring.hiker.CommentInfo;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 브랜드 태그 모니터링 전용 스토어(2026-08-06 스펙 + 전면 전용 스키마 개정) —
 * StoreTest와 같은 Testcontainers 관용구. 캠페인 테이블은 여기서 일절 건드리지 않는다.
 */
class BrandStoreTest {

	private JdbcTemplate db;
	private BrandRepository brands;
	private TaggedPostRepository taggedPosts;
	private AuthorProfileRepository authors;
	private BrandSnapshotRepository snapshots;
	private BrandPostMetaRepository postMeta;
	private BrandCommentRepository comments;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		brands = new BrandRepository(db);
		taggedPosts = new TaggedPostRepository(db);
		authors = new AuthorProfileRepository(db);
		snapshots = new BrandSnapshotRepository(db);
		postMeta = new BrandPostMetaRepository(db);
		comments = new BrandCommentRepository(db);
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
		assertThat(row.lastSweptOn()).isNull();                // 재가입 시 초기화 — "수집 준비 중" 복귀
	}

	@Test
	void 스윕_완주일과_프로필_갱신() {
		long id = brands.insertOrReactivate("brandx", "111", 1000L, "소개");
		brands.touchSwept(id, LocalDate.of(2026, 8, 6));
		assertThat(brands.findByUsername("brandx").orElseThrow().lastSweptOn())
				.isEqualTo(LocalDate.of(2026, 8, 6));
		brands.refreshProfile(id, 1500L, "새 소개");
		assertThat(db.queryForObject(
				"SELECT followers FROM brand_account WHERE id = " + id, Long.class)).isEqualTo(1500L);
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
		authors.upsert(new AuthorInfo("999", "creator", "이름", 100L, 10L, 5L, "bio", "https://p", false, null));
		assertThat(authors.freshIgUserIds(Set.of("999", "888"),
				Instant.now().minusSeconds(30L * 24 * 3600)))
				.containsExactly("999");                       // 888은 미보유 → 콜 필요
		assertThat(authors.freshIgUserIds(Set.of("999"), Instant.now().plusSeconds(60))).isEmpty();
		assertThat(authors.freshIgUserIds(List.of(), Instant.now())).isEmpty();
		authors.upsert(new AuthorInfo("999", "creator", "이름", 200L, 10L, 5L, "bio2", "https://p", true, null));
		assertThat(db.queryForObject(
				"SELECT followers FROM author_profile WHERE ig_user_id='999'", Long.class)).isEqualTo(200L);
		assertThat(db.queryForObject("SELECT count(*) FROM author_profile", Long.class)).isEqualTo(1L);
	}

	// ── brand_post_snapshot — 캠페인 SnapshotRepository 동형 규칙 이식 검증 ────

	@Test
	void 스냅샷_upsert와_fb_캐리포워드() {
		LocalDate d1 = LocalDate.of(2026, 8, 1);
		LocalDate d2 = LocalDate.of(2026, 8, 2);
		// 1일차: fb 관측(views = ig 1000 + fb 50)
		snapshots.upsertPost(d1, post("ReelA", 1754000000L, 1000L, 50L));
		assertThat(snapshotViews("ReelA", d1)).isEqualTo(1050L);
		// 2일차: fb 미관측(IG 전용 세션) — 직전 fb를 캐리포워드해 시계열 역행 방지
		snapshots.upsertPost(d2, post("ReelA", 1754000000L, 1100L, null));
		assertThat(snapshotViews("ReelA", d2)).isEqualTo(1150L);
	}

	@Test
	void 스냅샷_fb_첫_관측_역전파() {
		LocalDate d1 = LocalDate.of(2026, 8, 1);
		LocalDate d2 = LocalDate.of(2026, 8, 2);
		snapshots.upsertPost(d1, post("ReelB", 1754000000L, 1000L, null));   // fb 미관측
		snapshots.upsertPost(d2, post("ReelB", 1754000000L, 1100L, 80L));    // 첫 fb 관측
		// 이전 미관측 행에 소급 — 안 하면 관측 시작일에 +80 유령 점프
		assertThat(snapshotViews("ReelB", d1)).isEqualTo(1080L);
	}

	@Test
	void 스냅샷_0_캐리_판정() {
		LocalDate yesterday = LocalDate.of(2026, 8, 5);
		LocalDate today = LocalDate.of(2026, 8, 6);
		// 전일 reposts 0으로 종료 + 양수 이력 전무 → 캐리 대상
		snapshots.upsertPost(yesterday, post("ReelC", 1754000000L, null, null).mergedMetrics(null, null, 0L));
		assertThat(snapshots.codesWithRepostsZeroCarry(Set.of("ReelC"), today)).containsExactly("ReelC");
		// 양수 이력이 생기면 자동 해제
		snapshots.upsertPost(today, post("ReelC", 1754000000L, null, null).mergedMetrics(null, null, 3L));
		assertThat(snapshots.codesWithRepostsZeroCarry(Set.of("ReelC"), today.plusDays(1))).isEmpty();
	}

	@Test
	void 브랜드_프로필_추이_적재() {
		var profile = new ProfileInfo("brandx", "111", 1000L, 10L, 5L, "브랜드", "https://p", "소개", null, null, "{}");
		snapshots.upsertBrandProfile("brandx", LocalDate.of(2026, 8, 6), profile);
		snapshots.upsertBrandProfile("brandx", LocalDate.of(2026, 8, 6), profile);   // 같은 날 재수집 덮어쓰기
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_profile_snapshot WHERE username='brandx'", Long.class))
				.isEqualTo(1L);
	}

	// ── brand_post_meta / brand_post_comment ─────────────────────────────────

	@Test
	void 게시물_메타는_썸네일_보존_upsert다() {
		postMeta.upsert("CodeA", "creator", "REELS", LocalDate.of(2026, 8, 1), "캡션", "https://thumb1");
		postMeta.upsert("CodeA", "creator", "REELS", LocalDate.of(2026, 8, 1), "캡션 수정", null);
		assertThat(db.queryForObject(
				"SELECT caption FROM brand_post_meta WHERE short_code='CodeA'", String.class))
				.isEqualTo("캡션 수정");
		assertThat(db.queryForObject(
				"SELECT thumbnail_url FROM brand_post_meta WHERE short_code='CodeA'", String.class))
				.isEqualTo("https://thumb1");   // null이 기존 유효 썸네일을 지우지 않는다
	}

	@Test
	void 댓글은_누적_upsert되고_id_집합을_조회한다() {
		comments.upsertForPost("CodeA", List.of(
				new CommentInfo("c1", "user1", "본문1", 1L, Instant.now(), null)));
		comments.upsertForPost("CodeA", List.of(
				new CommentInfo("c1", "user1", "본문1 수정", 9L, Instant.now(), null),
				new CommentInfo("c2", "user2", "본문2", 2L, Instant.now(), "답글")));
		assertThat(comments.findIds("CodeA")).containsExactlyInAnyOrder("c1", "c2");
		assertThat(comments.findIds("없는코드")).isEmpty();
		assertThat(db.queryForObject(
				"SELECT like_count FROM brand_post_comment WHERE short_code='CodeA' AND id='c1'",
				Long.class)).isEqualTo(9L);
	}

	private Long snapshotViews(String code, LocalDate on) {
		return db.queryForObject(
				"SELECT views FROM brand_post_snapshot WHERE short_code=? AND captured_on=?",
				Long.class, code, on);
	}

	private static PostInfo post(String code, long takenAt) {
		return post(code, takenAt, 100L, null);
	}

	private static PostInfo post(String code, long takenAt, Long views, Long fbPlays) {
		return new PostInfo(code, "creator", null, null, "999", "REELS", "캡션", null,
				takenAt, 10L, 2L, views, fbPlays, null, null, null, null, null, null, "{}", true, false, false);
	}
}
