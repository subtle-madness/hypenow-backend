package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.AuthorInfo;
import com.celfit.instagram.source.CommentInfo;
import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.ProfileInfo;
import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.testsupport.TestDb;
import java.sql.Timestamp;
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
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		assertThat(brands.findActive()).hasSize(1);
		assertThat(brands.close("brandx")).isTrue();
		assertThat(brands.findActive()).isEmpty();
		assertThat(brands.close("brandx")).isFalse();          // 이미 닫힘 — 멱등
		long reId = brands.insertOrReactivate("brandx", profile("brandx", "111", 2000L, "소개2"), 12, true);
		assertThat(reId).isEqualTo(id);                        // 같은 행 재활성(UNIQUE username)
		BrandRow row = brands.findByUsername("brandx").orElseThrow();
		assertThat(row.status()).isEqualTo(BrandStatus.ACTIVE);
		assertThat(row.lastSweptOn()).isNull();                // 재가입 시 초기화 — "수집 준비 중" 복귀
	}

	@Test
	void 등록은_프로필_전필드를_적재한다() {
		long id = brands.insertOrReactivate("brandx", new ProfileInfo("brandx", "111", 1000L, 10L, 5L,
				"브랜드", "https://pic", "소개", true, "https://link"), 12, true);
		assertThat(column(id, "full_name", String.class)).isEqualTo("브랜드");
		assertThat(column(id, "profile_pic_url", String.class)).isEqualTo("https://pic");
		assertThat(column(id, "is_verified", Boolean.class)).isTrue();
		assertThat(column(id, "external_url", String.class)).isEqualTo("https://link");
		assertThat(column(id, "following", Long.class)).isEqualTo(10L);
		assertThat(column(id, "media_count", Long.class)).isEqualTo(5L);
	}

	@Test
	void 스윕_완주일과_프로필_갱신() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		brands.touchSwept(id, LocalDate.of(2026, 8, 6));
		assertThat(brands.findByUsername("brandx").orElseThrow().lastSweptOn())
				.isEqualTo(LocalDate.of(2026, 8, 6));
		brands.refreshProfile(id, profile("brandx", "111", 1500L, "새 소개"));
		assertThat(db.queryForObject(
				"SELECT followers FROM brand_account WHERE id = " + id, Long.class)).isEqualTo(1500L);
	}

	@Test
	void markServing은_last_swept_at만_당기고_완주_컬럼은_건드리지_않는다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);

		brands.markServing(id);

		assertThat(column(id, "last_swept_at", Timestamp.class)).isNotNull();   // was ready 신호만
		assertThat(brands.findByUsername("brandx").orElseThrow().lastSweptOn()).isNull();
		assertThat(column(id, "backfill_completed_at", Timestamp.class)).isNull();
	}

	@Test
	void markServing은_이미_서빙_중이면_시각을_덮지_않는다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		brands.touchSwept(id, LocalDate.of(2026, 8, 6));   // 완주 — last_swept_at 확정
		Timestamp sweptAt = column(id, "last_swept_at", Timestamp.class);

		brands.markServing(id);   // 가드(IS NULL) — no-op이어야 한다

		assertThat(column(id, "last_swept_at", Timestamp.class)).isEqualTo(sweptAt);
	}

	@Test
	void refreshProfile은_전필드를_갱신한다() {
		long id = brands.insertOrReactivate("brand_z", profile("brand_z"), 12, true);
		brands.refreshProfile(id, new ProfileInfo("brand_z", "1", 10L, 5L, 3L,
				"이름", "https://pic", "소개", true, "https://link"));
		assertThat(column(id, "full_name", String.class)).isEqualTo("이름");
		assertThat(column(id, "profile_pic_url", String.class)).isEqualTo("https://pic");
		assertThat(column(id, "is_verified", Boolean.class)).isTrue();
		assertThat(column(id, "external_url", String.class)).isEqualTo("https://link");
		assertThat(column(id, "following", Long.class)).isEqualTo(5L);
		assertThat(column(id, "media_count", Long.class)).isEqualTo(3L);
		assertThat(column(id, "biography", String.class)).isEqualTo("소개");
	}

	@Test
	void touchSwept는_완주시각과_오류클리어까지_기록한다() {
		long id = brands.insertOrReactivate("brand_x", profile("brand_x"), 12, true);
		brands.markBackfillError(id, "백필 실패: 타임아웃");
		assertThat(column(id, "backfill_error", String.class)).isEqualTo("백필 실패: 타임아웃");
		brands.touchSwept(id, LocalDate.of(2026, 8, 7));
		assertThat(column(id, "backfill_error", String.class)).isNull();
		assertThat(column(id, "last_swept_at", Timestamp.class)).isNotNull();
		assertThat(column(id, "backfill_completed_at", Timestamp.class)).isNotNull();
	}

	@Test
	void backfill_completed_at은_최초_완주시각을_보존한다() {
		long id = brands.insertOrReactivate("brand_x", profile("brand_x"), 12, true);
		brands.touchSwept(id, LocalDate.of(2026, 8, 7));
		Timestamp first = column(id, "backfill_completed_at", Timestamp.class);
		brands.touchSwept(id, LocalDate.of(2026, 8, 8));
		assertThat(column(id, "backfill_completed_at", Timestamp.class)).isEqualTo(first);
		assertThat(column(id, "last_swept_at", Timestamp.class)).isNotNull();   // 이쪽은 매번 전진
	}

	@Test
	void markBackfillError는_ready_이후엔_덮지_않는다() {
		long id = brands.insertOrReactivate("brand_y", profile("brand_y"), 12, true);
		brands.touchSwept(id, LocalDate.of(2026, 8, 7));
		brands.markBackfillError(id, "늦게 온 실패");
		assertThat(column(id, "backfill_error", String.class)).isNull();
	}

	@Test
	void 재가입은_백필_상태를_초기화한다() {
		// 재등록 = 백필을 처음부터 다시 — "수집 준비 중"으로 되돌아야 was 폴링이 collecting을 본다.
		long id = brands.insertOrReactivate("brand_y", profile("brand_y"), 12, true);
		brands.markBackfillError(id, "백필 실패");
		brands.close("brand_y");
		brands.insertOrReactivate("brand_y", profile("brand_y"), 12, true);
		assertThat(column(id, "backfill_error", String.class)).isNull();

		brands.touchSwept(id, LocalDate.of(2026, 8, 7));
		brands.close("brand_y");
		brands.insertOrReactivate("brand_y", profile("brand_y"), 12, true);
		assertThat(column(id, "backfill_completed_at", Timestamp.class)).isNull();
	}

	@Test
	void 수집_창은_요청값으로_저장되고_재가입에도_줄지_않는다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 3, true);
		assertThat(brands.findByUsername("brandx").orElseThrow().collectionMonths()).isEqualTo(3);
		// 재가입 확대는 반영, 축소는 GREATEST가 막는다 — "수집된 사실이 정본"(스펙 결정 요약).
		brands.close("brandx");
		brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 6, true);
		assertThat(brands.findByUsername("brandx").orElseThrow().collectionMonths()).isEqualTo(6);
		brands.close("brandx");
		brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 1, true);
		assertThat(brands.findByUsername("brandx").orElseThrow().collectionMonths()).isEqualTo(6);
	}

	@Test
	void 값_공간_밖_수집_창은_CHECK가_거절한다() {
		assertThatThrownBy(() -> brands.insertOrReactivate("brandx",
				profile("brandx", "111", 1000L, "소개"), 2, true))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void expandWindow는_창_상향과_백필_재개_신호를_함께_기록한다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 3, true);
		brands.touchSwept(id, LocalDate.now());   // 완주 상태를 만들어 둔다
		java.time.OffsetDateTime before = db.queryForObject(
				"SELECT collection_started_at FROM brand_account WHERE id = ?",
				java.time.OffsetDateTime.class, id);

		brands.expandWindow(id, 12);

		BrandRow row = brands.findByUsername("brandx").orElseThrow();
		assertThat(row.collectionMonths()).isEqualTo(12);
		assertThat(row.lastSweptOn()).isNull();   // 백필 재개 신호 — 다음 스윕 백스톱 상속
		assertThat(db.queryForObject("SELECT collection_started_at FROM brand_account WHERE id = ?",
				java.time.OffsetDateTime.class, id)).isAfter(before);   // FE 폴링 앵커 갱신
		// 08-13 개정: 완주 이력도 리셋한다 — 보존하면 FE 폴링(collectionCompletedAt != null)이
		// 확장 시작 즉시 멎는다. 확장 완주 시 touchSwept가 다시 채운다.
		assertThat(db.queryForObject("SELECT backfill_completed_at FROM brand_account WHERE id = ?",
				java.time.OffsetDateTime.class, id)).isNull();
	}

	/**
	 * 기간 확장은 완주 시각도 리셋한다(2026-08-13) — FE 폴링 종료 조건이
	 * collectionCompletedAt != null이라, 보존하면 확장 시작 즉시 폴링이 멎어 확장분이 화면에
	 * 반영되지 않는다.
	 */
	@Test
	void 기간_확장이_완주_시각을_리셋한다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 3, true);
		brands.touchSwept(id, LocalDate.now());
		assertThat(column(id, "backfill_completed_at", Timestamp.class)).isNotNull();

		assertThat(brands.expandWindow(id, 12)).isTrue();

		assertThat(column(id, "backfill_completed_at", Timestamp.class)).isNull();
	}

	@Test
	void expandWindow는_이미_더_큰_창이면_아무_흔적도_남기지_않는다() {
		// 동시 확장 경합의 순차 재현 — 12 요청이 먼저 반영된 뒤, 같은 옛 값(3)을 읽고 호출자 게이트를
		// 통과한 6 요청이 늦게 도착하는 상황. 축소는 물론 백필 재개 신호(last_swept_on)·폴링 앵커
		// (collection_started_at) 리셋 같은 부수효과도 남기면 안 된다.
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 3, true);
		assertThat(brands.expandWindow(id, 12)).isTrue();
		brands.touchSwept(id, LocalDate.of(2026, 8, 7));   // 확장 백필 완주 — 불변 확인용 상태
		java.time.OffsetDateTime startedAt = db.queryForObject(
				"SELECT collection_started_at FROM brand_account WHERE id = ?",
				java.time.OffsetDateTime.class, id);

		assertThat(brands.expandWindow(id, 6)).isFalse();   // 창이 실제로 커졌는가 = UPDATE rowcount

		BrandRow row = brands.findByUsername("brandx").orElseThrow();
		assertThat(row.collectionMonths()).isEqualTo(12);                      // GREATEST — 축소 차단
		assertThat(row.lastSweptOn()).isEqualTo(LocalDate.of(2026, 8, 7));     // 백필 재개 신호 미발생
		assertThat(db.queryForObject("SELECT collection_started_at FROM brand_account WHERE id = ?",
				java.time.OffsetDateTime.class, id)).isEqualTo(startedAt);     // 폴링 앵커 불변
	}

	// ── 창 커버리지 영속화(수집 상한 v2 — 2026-08-19 스펙 §7-1·§7-2) ──────────

	@Test
	void 커버리지는_미컷으로_시작하고_updateCoverage가_왕복한다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);

		assertThat(brands.coverage(id)).isEqualTo(new BrandRepository.Coverage(false, null));

		Instant depth = Instant.parse("2026-05-01T00:00:00Z");
		brands.updateCoverage(id, true, depth);
		assertThat(brands.coverage(id)).isEqualTo(new BrandRepository.Coverage(true, depth));

		// 재백필 완주는 컷 기록을 되돌린다 — 완주 = 요청 창 전체 커버(capped=false + NULL).
		brands.updateCoverage(id, false, null);
		assertThat(brands.coverage(id)).isEqualTo(new BrandRepository.Coverage(false, null));
	}

	@Test
	void 미존재_브랜드의_커버리지는_미컷_단면이다() {
		// 소비처가 "컷이면 열거 깊이 클램프"라 미지 브랜드는 클램프하지 않는 쪽이 안전하다.
		assertThat(brands.coverage(999_999L)).isEqualTo(new BrandRepository.Coverage(false, null));
	}

	@Test
	void raiseWindowCapped는_창과_커버리지만_올리고_수집_상태는_건드리지_않는다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 3, true);
		brands.touchSwept(id, LocalDate.of(2026, 8, 7));   // 완주 상태 — 불변이어야 한다
		Instant fallback = Instant.parse("2026-05-19T00:00:00Z");

		assertThat(brands.raiseWindowCapped(id, 12, fallback)).isTrue();

		BrandRow row = brands.findByUsername("brandx").orElseThrow();
		assertThat(row.collectionMonths()).isEqualTo(12);
		assertThat(brands.coverage(id)).isEqualTo(new BrandRepository.Coverage(true, fallback));
		// 백필을 제출하지 않는 경로라 재개 신호·폴링 종료 조건은 그대로여야 한다.
		assertThat(row.lastSweptOn()).isEqualTo(LocalDate.of(2026, 8, 7));
		assertThat(column(id, "backfill_completed_at", Timestamp.class)).isNotNull();
	}

	@Test
	void raiseWindowCapped는_기존_covered_until을_덮지_않는다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 3, true);
		Instant actualDepth = Instant.parse("2026-06-01T00:00:00Z");
		brands.updateCoverage(id, true, actualDepth);   // 직전 백필이 기록한 실수집 깊이

		assertThat(brands.raiseWindowCapped(id, 12, Instant.parse("2026-05-19T00:00:00Z"))).isTrue();

		// COALESCE — 폴백(기존 창 컷)은 실측값이 없을 때만 쓰는 근사다.
		assertThat(brands.coverage(id)).isEqualTo(new BrandRepository.Coverage(true, actualDepth));
	}

	@Test
	void raiseWindowCapped는_이미_더_큰_창이면_아무_흔적도_남기지_않는다() {
		// expandWindow와 같은 rowcount 판정 — 경합에서 진 요청이 커버리지만 컷으로 뒤집으면 안 된다.
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);

		assertThat(brands.raiseWindowCapped(id, 6, Instant.parse("2026-05-19T00:00:00Z"))).isFalse();

		assertThat(brands.findByUsername("brandx").orElseThrow().collectionMonths()).isEqualTo(12);
		assertThat(brands.coverage(id)).isEqualTo(new BrandRepository.Coverage(false, null));
	}

	@Test
	void nthNewestTagTakenAt은_n번째_최신_태그_행의_taken_at을_준다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		long other = brands.insertOrReactivate("brandy", profile("brandy", "222", 2000L, "소개"), 12, true);
		assertThat(taggedPosts.nthNewestTagTakenAt(id, 1)).isEmpty();   // 행 0개 — 컷 안 걸림

		taggedPosts.insert(id, post("New", 1754000300L));
		taggedPosts.insert(id, post("Mid", 1754000200L));
		taggedPosts.insert(id, post("Old", 1754000100L));
		taggedPosts.upsertDirect(id, post("Mid", 1754000200L), Instant.now());   // 겹침 — 태그 행이라 센다
		taggedPosts.insert(other, post("Other", 1754000000L));                   // 다른 브랜드는 제외

		assertThat(taggedPosts.nthNewestTagTakenAt(id, 1))
				.contains(Instant.ofEpochSecond(1754000300L));
		assertThat(taggedPosts.nthNewestTagTakenAt(id, 2))
				.contains(Instant.ofEpochSecond(1754000200L));
		assertThat(taggedPosts.nthNewestTagTakenAt(id, 3))
				.contains(Instant.ofEpochSecond(1754000100L));
		assertThat(taggedPosts.nthNewestTagTakenAt(id, 4)).isEmpty();   // 행이 n개 미만
		assertThat(taggedPosts.nthNewestTagTakenAt(id, 0)).isEmpty();   // 무제한(상한 0 이하)
	}

	@Test
	void nthNewestTagTakenAt은_순수_direct_행을_모수에서_뺀다() {
		// 상한은 태그 열거를 지배하고 direct 등록 게시물은 상한 밖이다(§7-3) — 순수 direct 행을
		// 세면 태그 미달 브랜드가 확장 스킵으로 부당하게 걸려 재백필 기회를 잃는다.
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("Tagged", 1754000300L));
		taggedPosts.upsertDirect(id, post("DirectOnly", 1754000200L), Instant.now());

		assertThat(taggedPosts.nthNewestTagTakenAt(id, 1))
				.contains(Instant.ofEpochSecond(1754000300L));
		assertThat(taggedPosts.nthNewestTagTakenAt(id, 2)).isEmpty();
	}

	@Test
	void 태그_게시물_링크와_댓글_게이트_상태() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", null, null), 12, true);
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
		authors.upsert(new AuthorInfo("999", "creator", "이름", 100L, 10L, 5L, "bio", "https://p", false, true));
		assertThat(authors.freshIgUserIds(Set.of("999", "888"),
				Instant.now().minusSeconds(30L * 24 * 3600)))
				.containsExactly("999");                       // 888은 미보유 → 콜 필요
		assertThat(authors.freshIgUserIds(Set.of("999"), Instant.now().plusSeconds(60))).isEmpty();
		assertThat(authors.freshIgUserIds(List.of(), Instant.now())).isEmpty();
		assertThat(db.queryForObject(
				"SELECT is_verified FROM author_profile WHERE ig_user_id='999'", Boolean.class)).isTrue();
		authors.upsert(new AuthorInfo("999", "creator", "이름", 200L, 10L, 5L, "bio2", "https://p", true, null));
		assertThat(db.queryForObject(
				"SELECT followers FROM author_profile WHERE ig_user_id='999'", Long.class)).isEqualTo(200L);
		// 인증뱃지는 관측값 그대로 — 키 부재(null)를 "미인증"으로 굳히지 않는다(AuthorInfo 주석).
		assertThat(db.queryForObject(
				"SELECT is_verified FROM author_profile WHERE ig_user_id='999'", Boolean.class)).isNull();
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
		var profile = new ProfileInfo("brandx", "111", 1000L, 10L, 5L, "브랜드", "https://p", "소개", null, null);
		snapshots.upsertBrandProfile("brandx", LocalDate.of(2026, 8, 6), profile);
		snapshots.upsertBrandProfile("brandx", LocalDate.of(2026, 8, 6), profile);   // 같은 날 재수집 덮어쓰기
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_profile_snapshot WHERE username='brandx'", Long.class))
				.isEqualTo(1L);
	}

	// ── brand_post_meta / brand_post_comment ─────────────────────────────────

	@Test
	void 게시물_메타는_썸네일_보존_upsert다() {
		postMeta.upsert("CodeA", "creator", "REELS", LocalDate.of(2026, 8, 1), "캡션", "https://thumb1",
				null, null, null);
		postMeta.upsert("CodeA", "creator", "REELS", LocalDate.of(2026, 8, 1), "캡션 수정", null,
				null, null, null);
		assertThat(db.queryForObject(
				"SELECT caption FROM brand_post_meta WHERE short_code='CodeA'", String.class))
				.isEqualTo("캡션 수정");
		assertThat(db.queryForObject(
				"SELECT thumbnail_url FROM brand_post_meta WHERE short_code='CodeA'", String.class))
				.isEqualTo("https://thumb1");   // null이 기존 유효 썸네일을 지우지 않는다
	}

	@Test
	void 게시물_메타는_영상_협찬_필드를_적재한다() {
		postMeta.upsert("CodeB", "creator", "REELS", LocalDate.of(2026, 8, 1), "캡션", "https://thumb1",
				"https://video.mp4", 12.5, true);
		assertThat(db.queryForObject(
				"SELECT video_url FROM brand_post_meta WHERE short_code='CodeB'", String.class))
				.isEqualTo("https://video.mp4");
		assertThat(db.queryForObject(
				"SELECT video_duration FROM brand_post_meta WHERE short_code='CodeB'", Double.class))
				.isEqualTo(12.5);
		assertThat(db.queryForObject(
				"SELECT is_paid_partnership FROM brand_post_meta WHERE short_code='CodeB'", Boolean.class))
				.isTrue();
		// 재관측은 영상 URL·길이를 새 값으로 갱신하고(서명 만료 방어), 협찬 판정은 키 부재
		// (null=unknown)를 그대로 반영한다(PostInfo 주석 §3-2 — 보존하면 협찬 해제를 못 따라간다).
		postMeta.upsert("CodeB", "creator", "REELS", LocalDate.of(2026, 8, 1), "캡션", "https://thumb1",
				"https://video2.mp4", 30.0, null);
		assertThat(db.queryForObject(
				"SELECT video_url FROM brand_post_meta WHERE short_code='CodeB'", String.class))
				.isEqualTo("https://video2.mp4");
		assertThat(db.queryForObject(
				"SELECT video_duration FROM brand_post_meta WHERE short_code='CodeB'", Double.class))
				.isEqualTo(30.0);
		assertThat(db.queryForObject(
				"SELECT is_paid_partnership FROM brand_post_meta WHERE short_code='CodeB'", Boolean.class))
				.isNull();
	}

	/**
	 * 리뷰 I1 — 영상 URL·길이는 썸네일과 같은 CDN 서명 표시값이라 꽝 세션(키 부재)이 기존 값을
	 * 지우면 안 된다(세션 복권 실측 08-04: 같은 엔드포인트가 키를 실었다 뺐다 한다).
	 */
	@Test
	void 영상_필드_null_관측은_기존_값을_보존한다() {
		postMeta.upsert("CodeC", "creator", "REELS", LocalDate.of(2026, 8, 1), "캡션", "https://thumb1",
				"https://video.mp4", 12.5, true);
		postMeta.upsert("CodeC", "creator", "REELS", LocalDate.of(2026, 8, 1), "캡션", null,
				null, null, true);
		assertThat(db.queryForObject(
				"SELECT video_url FROM brand_post_meta WHERE short_code='CodeC'", String.class))
				.isEqualTo("https://video.mp4");
		assertThat(db.queryForObject(
				"SELECT video_duration FROM brand_post_meta WHERE short_code='CodeC'", Double.class))
				.isEqualTo(12.5);
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

	// ── 티어 판정 입력(정책 v1 — 2026-08-09 스펙 §6) ─────────────────────────

	@Test
	void 링크_last_crawled_at은_null로_시작하고_touchCrawled가_갱신한다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("A", 1754000000L));
		taggedPosts.insert(id, post("B", 1754000000L));
		Instant floor = Instant.ofEpochSecond(1754000000L).minusSeconds(60);

		assertThat(taggedPosts.trackedPosts(id, floor)).hasSize(2)
				.allMatch(t -> t.lastCrawledAt() == null);

		Instant at = Instant.parse("2026-08-09T03:00:00Z");
		taggedPosts.touchCrawled(id, List.of("A"), at);

		List<TaggedPostRepository.TrackedPost> after = taggedPosts.trackedPosts(id, floor);
		assertThat(after).filteredOn(t -> t.shortCode().equals("A"))
				.singleElement().satisfies(t -> assertThat(t.lastCrawledAt()).isEqualTo(at));
		assertThat(after).filteredOn(t -> t.shortCode().equals("B"))
				.singleElement().satisfies(t -> assertThat(t.lastCrawledAt()).isNull());
	}

	@Test
	void trackedPosts는_minTakenAt_이전_링크를_거른다() {
		// 추적 플로어(180일) 밖 링크는 티어 판정 입력에서 빠진다 — 영구 제외의 조회 측 절반
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("Recent", 1754000000L));
		taggedPosts.insert(id, post("Ancient", 1700000000L));

		assertThat(taggedPosts.trackedPosts(id, Instant.ofEpochSecond(1750000000L)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode).containsExactly("Recent");
	}

	@Test
	void touchCrawledDepth는_컷_이후_링크_전부를_갱신한다() {
		// 자연 종료한 열거가 커버한 깊이 전체 갱신 — 열거에 안 실린 링크(삭제·태그 제거·비공개)도
		// 포함해야 due가 영구 true로 굳지 않는다. 컷 이전 링크는 건드리지 않는다.
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("InCut", 1754000000L));
		taggedPosts.insert(id, post("OnCut", 1753000000L));      // 경계 — 컷과 동일 시각(포함)
		taggedPosts.insert(id, post("BeforeCut", 1752000000L));
		Instant at = Instant.parse("2026-08-09T09:00:00Z");

		taggedPosts.touchCrawledDepth(id, Instant.ofEpochSecond(1753000000L), at);

		List<TaggedPostRepository.TrackedPost> after =
				taggedPosts.trackedPosts(id, Instant.ofEpochSecond(1700000000L));
		assertThat(after).filteredOn(t -> t.shortCode().equals("InCut"))
				.singleElement().satisfies(t -> assertThat(t.lastCrawledAt()).isEqualTo(at));
		assertThat(after).filteredOn(t -> t.shortCode().equals("OnCut"))
				.singleElement().satisfies(t -> assertThat(t.lastCrawledAt()).isEqualTo(at));
		assertThat(after).filteredOn(t -> t.shortCode().equals("BeforeCut"))
				.singleElement().satisfies(t -> assertThat(t.lastCrawledAt()).isNull());
	}

	@Test
	void touchCrawled는_빈_목록에_쿼리를_내지_않는다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.touchCrawled(id, List.of(), Instant.now());   // 예외 없이 no-op이면 통과
	}

	// ── direct 게시물 파이프라인 통합(2026-08-18 설계 §3-1·§5-3) ─────────────

	@Test
	void 마이그레이션_후_기존_tagged_행은_tag_detected_at이_채워진다() {
		// V20260818040742의 백필(UPDATE ... SET tag_detected_at = first_seen_at)이 실제로 도는지
		// — insert()가 이제 tag_detected_at을 명시적으로 채우므로, 신규 삽입행으로 이를 검증한다.
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("A", 1754000000L));
		assertThat(db.queryForObject(
				"SELECT tag_detected_at FROM brand_tagged_post WHERE brand_id=? AND short_code='A'",
				Timestamp.class, id)).isNotNull();
	}

	@Test
	void upsertDirect_신규_삽입은_tag_detected_at이_비고_direct_registered_at이_채워진다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		Instant registeredAt = Instant.parse("2026-08-18T03:00:00Z");

		taggedPosts.upsertDirect(id, post("D1", 1754000000L), registeredAt);

		assertThat(db.queryForObject(
				"SELECT tag_detected_at FROM brand_tagged_post WHERE brand_id=? AND short_code='D1'",
				Timestamp.class, id)).isNull();
		assertThat(db.queryForObject(
				"SELECT direct_registered_at FROM brand_tagged_post WHERE brand_id=? AND short_code='D1'",
				Timestamp.class, id).toInstant()).isEqualTo(registeredAt);
	}

	@Test
	void upsertDirect를_기존_tagged_행에_적용하면_tag_detected_at은_보존되고_direct_registered_at만_채워진다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("Overlap", 1754000000L));
		Timestamp before = db.queryForObject(
				"SELECT tag_detected_at FROM brand_tagged_post WHERE brand_id=? AND short_code='Overlap'",
				Timestamp.class, id);
		Instant registeredAt = Instant.parse("2026-08-18T03:00:00Z");

		taggedPosts.upsertDirect(id, post("Overlap", 1754000000L), registeredAt);

		assertThat(db.queryForObject(
				"SELECT tag_detected_at FROM brand_tagged_post WHERE brand_id=? AND short_code='Overlap'",
				Timestamp.class, id)).isEqualTo(before);
		assertThat(db.queryForObject(
				"SELECT direct_registered_at FROM brand_tagged_post WHERE brand_id=? AND short_code='Overlap'",
				Timestamp.class, id).toInstant()).isEqualTo(registeredAt);
	}

	@Test
	void insert_열거를_기존_direct_only_행에_적용하면_direct_registered_at은_보존되고_tag_detected_at이_채워진다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		Instant registeredAt = Instant.parse("2026-08-18T03:00:00Z");
		taggedPosts.upsertDirect(id, post("D2", 1754000000L), registeredAt);

		taggedPosts.insert(id, post("D2", 1754000000L));   // 열거가 뒤늦게 같은 게시물을 만남

		assertThat(db.queryForObject(
				"SELECT direct_registered_at FROM brand_tagged_post WHERE brand_id=? AND short_code='D2'",
				Timestamp.class, id).toInstant()).isEqualTo(registeredAt);
		assertThat(db.queryForObject(
				"SELECT tag_detected_at FROM brand_tagged_post WHERE brand_id=? AND short_code='D2'",
				Timestamp.class, id)).isNotNull();
	}

	@Test
	void trackedPosts는_direct_행을_전부_제외한다() {
		// R5 — 가드(AND tag_detected_at IS NOT NULL)를 지운 채로 먼저 실패를 확인했다(수동 검증,
		// 아래 주석 참조). direct-only 행은 열거가 만날 수 없어서, 겹침 행은 상한 밖(2026-08-19
		// 수집 상한 v2 §7-3)이라서 — 둘 다 열거 깊이 결정 입력에서 빠진다.
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("Tagged", 1754000000L));
		taggedPosts.upsertDirect(id, post("DirectOnly", 1754000000L), Instant.now());
		taggedPosts.insert(id, post("Overlap", 1754000000L));
		taggedPosts.upsertDirect(id, post("Overlap", 1754000000L), Instant.now());

		assertThat(taggedPosts.trackedPosts(id, Instant.ofEpochSecond(1700000000L)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode).containsExactly("Tagged");
	}

	@Test
	void touchCrawledDepth는_direct_행의_last_crawled_at을_건드리지_않는다() {
		// 겹침 행까지 제외하는 것이 §7-3의 핵심이다 — 커버 간주 touch가 겹침 행을 찍으면 컷 밖
		// direct 게시물이 2단계에서도 due가 아니게 돼 "상한 밖" 규정이 무력화된다(동결 그대로).
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("Tagged", 1754000000L));
		taggedPosts.upsertDirect(id, post("DirectOnly", 1754000000L), Instant.now());
		taggedPosts.insert(id, post("Overlap", 1754000000L));
		taggedPosts.upsertDirect(id, post("Overlap", 1754000000L), Instant.now());
		Instant at = Instant.parse("2026-08-18T09:00:00Z");

		taggedPosts.touchCrawledDepth(id, Instant.ofEpochSecond(1700000000L), at);

		assertThat(db.queryForObject(
				"SELECT last_crawled_at FROM brand_tagged_post WHERE brand_id=? AND short_code='Tagged'",
				Timestamp.class, id)).isEqualTo(Timestamp.from(at));
		assertThat(db.queryForObject(
				"SELECT last_crawled_at FROM brand_tagged_post WHERE brand_id=? AND short_code='DirectOnly'",
				Timestamp.class, id)).isNull();
		assertThat(db.queryForObject(
				"SELECT last_crawled_at FROM brand_tagged_post WHERE brand_id=? AND short_code='Overlap'",
				Timestamp.class, id)).isNull();
	}

	@Test
	void unenumeratedDuePosts는_겹침_행도_포함한다() {
		// §7-3 — 모든 direct 행이 2단계 모수다. 중복 콜 방지는 필터가 아니라 구조로 유지된다:
		// 1단계 열거가 실제로 만난 겹침 행은 touchCrawled로 갱신돼 due 판정에서 빠진다.
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("Tagged", 1754000000L));                          // 순수 태그 — 제외
		taggedPosts.upsertDirect(id, post("DirectOnly", 1754000000L), Instant.now());
		taggedPosts.insert(id, post("Overlap", 1754000000L));
		taggedPosts.upsertDirect(id, post("Overlap", 1754000000L), Instant.now());

		assertThat(taggedPosts.unenumeratedDuePosts(id, Instant.ofEpochSecond(1700000000L)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode)
				.containsExactlyInAnyOrder("DirectOnly", "Overlap");
	}

	@Test
	void unenumeratedDuePosts는_minTakenAt_이전_direct_행을_거른다() {
		// minTakenAt 인자로 나이 컷이 걸린다 — 상한만 면제다(§7-3 첫 줄). 다만 런타임 호출자가
		// 넘기는 값은 브랜드 창이 아니라 180일(BrandCrawlPolicy.TRACKED_MAX_AGE) 고정이다
		// (trackedPosts와 같은 추적 범위 컷) — 여기 12개월 창은 시드 편의일 뿐 판정에 안 쓰인다.
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.upsertDirect(id, post("Recent", 1754000000L), Instant.now());
		taggedPosts.upsertDirect(id, post("Ancient", 1700000000L), Instant.now());

		assertThat(taggedPosts.unenumeratedDuePosts(id, Instant.ofEpochSecond(1750000000L)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode).containsExactly("Recent");
	}

	@Test
	void clearDirect는_direct_표식만_해제하고_tagged_행은_남는다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("Overlap", 1754000000L));
		taggedPosts.upsertDirect(id, post("Overlap", 1754000000L), Instant.now());

		taggedPosts.clearDirect(id, "Overlap");

		assertThat(db.queryForObject(
				"SELECT direct_registered_at FROM brand_tagged_post WHERE brand_id=? AND short_code='Overlap'",
				Timestamp.class, id)).isNull();
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_tagged_post WHERE brand_id=? AND short_code='Overlap'",
				Long.class, id)).isEqualTo(1L);
	}

	@Test
	void clearDirect는_행이_없어도_무해하다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.clearDirect(id, "없는코드");   // 예외 없이 no-op이면 통과
	}

	@Test
	void deleteIfDirectOnly는_순수_direct_행만_지운다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("Overlap", 1754000000L));
		taggedPosts.upsertDirect(id, post("Overlap", 1754000000L), Instant.now());
		taggedPosts.upsertDirect(id, post("DirectOnly", 1754000000L), Instant.now());

		assertThat(taggedPosts.deleteIfDirectOnly(id, "Overlap")).isFalse();     // 겹침 — 안 지워짐
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_tagged_post WHERE brand_id=? AND short_code='Overlap'",
				Long.class, id)).isEqualTo(1L);

		assertThat(taggedPosts.deleteIfDirectOnly(id, "DirectOnly")).isTrue();   // 순수 direct — 지워짐
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_tagged_post WHERE brand_id=? AND short_code='DirectOnly'",
				Long.class, id)).isEqualTo(0L);

		assertThat(taggedPosts.deleteIfDirectOnly(id, "없는코드")).isFalse();     // 행 없음 — 멱등
	}

	@Test
	void findDirectSnapshot은_direct_등록된_행만_반환한다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		taggedPosts.insert(id, post("TaggedOnly", 1754000000L));
		Instant registeredAt = Instant.parse("2026-08-18T03:00:00Z");
		taggedPosts.upsertDirect(id, post("Direct1", 1754000000L), registeredAt);

		assertThat(taggedPosts.findDirectSnapshot(id, "TaggedOnly")).isEmpty();
		assertThat(taggedPosts.findDirectSnapshot(id, "Direct1")).isPresent()
				.get().satisfies(s -> {
					assertThat(s.shortCode()).isEqualTo("Direct1");
					assertThat(s.authorUsername()).isEqualTo("creator");
				});
	}

	// ── 보강 정산 마킹(2026-08-13 완결 배치 서빙 스펙 §1) ─────────────────────

	/**
	 * markEnriched는 was 목록 게이트(enriched_at IS NOT NULL)의 정본이라 실제 DB에 대고 왕복
	 * 검증한다 — 컬럼명 오타·인자 순서 뒤바뀜·스코핑 누락은 컴파일로는 안 잡히고, 통합 시점에
	 * "브랜드 목록이 조용히 빈다"로만 드러난다. 삽입 직후 NULL(미정산) → 지정 코드만 마킹 →
	 * 같은 브랜드의 다른 코드·다른 브랜드의 같은 코드는 NULL 유지 → 재마킹 무해(시각만 갱신).
	 */
	@Test
	void markEnriched는_지정_브랜드의_지정_코드에만_정산_시각을_찍는다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 12, true);
		long other = brands.insertOrReactivate("brandy", profile("brandy", "222", 2000L, "소개"), 12, true);
		taggedPosts.insert(id, post("A", 1754000000L));
		taggedPosts.insert(id, post("B", 1754000000L));
		taggedPosts.insert(other, post("A", 1754000000L));       // 다른 브랜드의 같은 코드

		assertThat(enrichedAt(id, "A")).isNull();                // 삽입 직후는 미정산
		assertThat(enrichedAt(id, "B")).isNull();

		taggedPosts.markEnriched(id, List.of(), Instant.now());  // 빈 목록은 no-op(빈 IN절은 SQL 오류)
		assertThat(enrichedAt(id, "A")).isNull();

		Instant at = Instant.parse("2026-08-13T03:00:00Z");
		taggedPosts.markEnriched(id, List.of("A"), at);

		assertThat(enrichedAt(id, "A")).isEqualTo(at);
		assertThat(enrichedAt(id, "B")).isNull();                // 같은 브랜드의 다른 코드 — 코드 스코핑
		assertThat(enrichedAt(other, "A")).isNull();             // 다른 브랜드의 같은 코드 — 브랜드 스코핑

		// 재마킹은 무해하다 — 시각만 갱신되고 노출 여부(NOT NULL)는 안 바뀐다.
		Instant again = Instant.parse("2026-08-13T09:00:00Z");
		taggedPosts.markEnriched(id, List.of("A"), again);
		assertThat(enrichedAt(id, "A")).isEqualTo(again);
		assertThat(enrichedAt(id, "B")).isNull();
	}

	/** enriched_at 직조회 — 저장소에 조회 API가 없다(읽는 쪽은 was의 SQL 게이트다). */
	private Instant enrichedAt(long brandId, String code) {
		Timestamp ts = db.queryForObject(
				"SELECT enriched_at FROM brand_tagged_post WHERE brand_id=? AND short_code=?",
				Timestamp.class, brandId, code);
		return ts == null ? null : ts.toInstant();
	}

	private Long snapshotViews(String code, LocalDate on) {
		return db.queryForObject(
				"SELECT views FROM brand_post_snapshot WHERE short_code=? AND captured_on=?",
				Long.class, code, on);
	}

	/** brand_account 컬럼 직조회 — 조회 API가 아직 없어 저장 여부를 SQL로 확인한다. */
	private <T> T column(long brandId, String name, Class<T> type) {
		return db.queryForObject("SELECT " + name + " FROM brand_account WHERE id = ?", type, brandId);
	}

	private static ProfileInfo profile(String username) {
		return profile(username, "1", 1L, null);
	}

	private static ProfileInfo profile(String username, String igUserId, Long followers, String biography) {
		return new ProfileInfo(username, igUserId, followers, null, null, null, null, biography,
				null, null);
	}

	private static PostInfo post(String code, long takenAt) {
		return post(code, takenAt, 100L, null);
	}

	private static PostInfo post(String code, long takenAt, Long views, Long fbPlays) {
		return new PostInfo(code, "creator", null, null, "999", "REELS", "캡션", null,
				takenAt, 10L, 2L, views, fbPlays, null, null, null, null, null, null, true, false, false);
	}
}
