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

	// ── 열거 커버 가드(설계 §2-5) ────────────────────────────────────────────

	/** hashtag 성분 행은 tagged 열거가 도달할 수 없다 — 열거 깊이 판정 모수에서 빠져야 한다. */
	@Test
	void trackedPosts는_hashtag_성분_행을_제외한다() {
		Instant takenAt = NOW.minusSeconds(86400);
		repo.insert(brandId, post("TAGONLY", "poster1", takenAt));
		repo.upsertHashtag(brandId, post("HASHONLY", "poster2", takenAt), NOW);
		repo.insert(brandId, post("BOTH", "poster3", takenAt));
		repo.upsertHashtag(brandId, post("BOTH", "poster3", takenAt), NOW);

		assertThat(repo.trackedPosts(brandId, takenAt.minusSeconds(1)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode)
				.containsExactly("TAGONLY");
	}

	/**
	 * 커버 간주 touch도 마찬가지 — 여기 걸리면 2단계 단건 수집의 due가 실크롤 없이 꺼진다. BOTH(겹침)
	 * 행으로 검증해야 한다: hashtag 성분만 있는 행은 tag_detected_at이 NULL이라 이 가드가 없어도
	 * 기존 tag_detected_at IS NOT NULL 조건에 걸려 애초에 안 건드려진다 — 신규 가드를 지우면 이 테스트가
	 * 거짓 통과한다. TAGONLY도 함께 심어 가드가 "아무것도 안 건드림"이 아니라 "hashtag 성분만 가림"을
	 * 증명한다.
	 */
	@Test
	void touchCrawledDepth는_hashtag_성분_행을_건드리지_않는다() {
		Instant takenAt = NOW.minusSeconds(86400);
		repo.insert(brandId, post("TAGONLY", "poster1", takenAt));
		repo.insert(brandId, post("BOTH", "poster2", takenAt));
		repo.upsertHashtag(brandId, post("BOTH", "poster2", takenAt), NOW);

		repo.touchCrawledDepth(brandId, takenAt.minusSeconds(1), NOW);

		assertThat(db.queryForObject(
				"SELECT last_crawled_at IS NULL FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'BOTH'",
				Boolean.class, brandId)).isTrue();
		assertThat(db.queryForObject(
				"SELECT last_crawled_at IS NOT NULL FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'TAGONLY'",
				Boolean.class, brandId)).isTrue();
	}

	/** 부재 검증은 tagged-only 전용 — hashtag 성분 행의 404는 2단계 단건 수집이 이미 잡는다. */
	@Test
	void tagVerifyCandidates는_hashtag_성분_행을_제외한다() {
		Instant takenAt = NOW.minusSeconds(86400);
		repo.insert(brandId, post("TAGONLY", "poster1", takenAt));
		repo.insert(brandId, post("BOTH", "poster3", takenAt));
		repo.upsertHashtag(brandId, post("BOTH", "poster3", takenAt), NOW);

		assertThat(repo.tagVerifyCandidates(brandId, takenAt.minusSeconds(1), NOW))
				.containsExactly("TAGONLY");
	}

	/**
	 * 2단계 모수는 direct ∪ hashtag — tagged-only만 빠진다. 미보강 행이 먼저 오고(이관분 우선 충전),
	 * 미보강 행끼리는 taken_at DESC(최신 우선) 보조 정렬이 걸린다 — DIRECT2를 HASHTAG보다 더 예전
	 * taken_at으로 심어 그 보조 정렬을 고정한다.
	 */
	@Test
	void unenumeratedDuePosts는_direct와_hashtag를_미보강_우선으로_돌려준다() {
		Instant takenAt = NOW.minusSeconds(86400);
		Instant olderTakenAt = NOW.minusSeconds(172800);
		repo.insert(brandId, post("TAGONLY", "poster1", takenAt));
		repo.upsertDirect(brandId, post("DIRECT", "poster2", takenAt), NOW);
		repo.upsertHashtag(brandId, post("HASHTAG", "poster3", takenAt), NOW);
		repo.upsertDirect(brandId, post("DIRECT2", "poster4", olderTakenAt), NOW);
		repo.markEnriched(brandId, List.of("DIRECT"), NOW);   // 보강 완료 — 뒤로 밀린다

		assertThat(repo.unenumeratedDuePosts(brandId, olderTakenAt.minusSeconds(1)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode)
				.containsExactly("HASHTAG", "DIRECT2", "DIRECT");
	}

	/**
	 * 기동 즉시 백필 모수(2026-08-28 사용자 지시) — direct∪hashtag 중 <b>enriched_at IS NULL</b>인
	 * 행만, taken_at DESC로 돌려준다. tagged-only 행(TAGONLY)은 애초에 모수 밖(unenumeratedDuePosts와
	 * 동일 population 가드)이라 제외되고, 이미 보강된 행(direct지만 enriched)도 제외된다.
	 */
	@Test
	void unenrichedUnenumeratedPosts는_미보강_direct_hashtag만_최신순으로_돌려준다() {
		Instant older = NOW.minusSeconds(172800);
		Instant newer = NOW.minusSeconds(86400);
		repo.insert(brandId, post("TAGONLY", "poster1", newer));   // 모수 밖 — tagged 열거가 담당
		repo.upsertDirect(brandId, post("ENRICHED", "poster2", newer), NOW);
		repo.markEnriched(brandId, List.of("ENRICHED"), NOW);       // 이미 보강 — 제외
		repo.upsertDirect(brandId, post("DIRECT", "poster3", older), NOW);
		repo.upsertHashtag(brandId, post("HASHTAG", "poster4", newer), NOW);

		assertThat(repo.unenrichedUnenumeratedPosts(brandId, older.minusSeconds(1)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode)
				.containsExactly("HASHTAG", "DIRECT");
	}

	/** minTakenAt 컷 밖(브랜드 창 밖)은 미보강이어도 제외된다 — 다른 조회 메서드와 같은 창 규칙. */
	@Test
	void unenrichedUnenumeratedPosts는_창_밖_행을_제외한다() {
		Instant inWindow = NOW.minusSeconds(86400);
		Instant outOfWindow = NOW.minusSeconds(200L * 86400);
		repo.upsertDirect(brandId, post("IN", "poster1", inWindow), NOW);
		repo.upsertDirect(brandId, post("OUT", "poster2", outOfWindow), NOW);

		assertThat(repo.unenrichedUnenumeratedPosts(brandId, inWindow.minusSeconds(1)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode)
				.containsExactly("IN");
	}

	/**
	 * 삭제·비공개로 확정된(markUnavailable) 행은 기동 백필 모수에서도 제외해야 한다(2026-08-28 리뷰
	 * 지적) — 이 행은 enriched_at을 영영 못 받으므로(비공개·삭제라 재보강 불가) 이 가드가 없으면
	 * 재기동마다 같은 404를 Hiker에 재과금하며 재확인한다. tagVerifyCandidates와 달리 여기는 재관측
	 * 자가 치유(touchCrawled가 unavailable_at을 해제)에 기대지 않는다 — 기동 백필은 자체 재시도
	 * 주기가 없다.
	 */
	@Test
	void unenrichedUnenumeratedPosts는_unavailable_마킹된_행을_제외한다() {
		Instant takenAt = NOW.minusSeconds(86400);
		repo.upsertDirect(brandId, post("GONE", "poster1", takenAt), NOW);
		repo.upsertDirect(brandId, post("ALIVE", "poster2", takenAt), NOW);
		repo.markUnavailable(brandId, "GONE", NOW);

		assertThat(repo.unenrichedUnenumeratedPosts(brandId, takenAt.minusSeconds(1)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode)
				.containsExactly("ALIVE");
	}

	/**
	 * direct 취소({@code deleteIfDirectOnly})가 hashtag 성분이 있는 겹침 행을 삭제하면 안 된다
	 * (설계 §2-4 — direct 취소는 direct 표식만 해제한다). 컨트롤러는 이 메서드가 false를 돌려주면
	 * {@link TaggedPostRepository#clearDirect}로 폴백한다.
	 */
	@Test
	void direct_취소는_hashtag_성분_행을_삭제하지_않는다() {
		repo.upsertHashtag(brandId, post("HHH", "poster1", NOW.minusSeconds(86400)), NOW);
		repo.upsertDirect(brandId, post("HHH", "poster1", NOW.minusSeconds(86400)), NOW);

		assertThat(repo.deleteIfDirectOnly(brandId, "HHH")).isFalse();
		assertThat(db.queryForObject(
				"SELECT hashtag_detected_at IS NOT NULL AND direct_registered_at IS NOT NULL"
						+ " FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'HHH'",
				Boolean.class, brandId)).isTrue();

		repo.clearDirect(brandId, "HHH");

		assertThat(db.queryForObject(
				"SELECT direct_registered_at IS NULL FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'HHH'",
				Boolean.class, brandId)).isTrue();
		assertThat(db.queryForObject("SELECT count(*) FROM brand_tagged_post WHERE brand_id = ?",
				Integer.class, brandId)).isEqualTo(1);
	}

	// ── 감시 세트 경계(2026-09-02 감시 세트 2,000 설계 §1·§3) ──────────────────

	@Test
	void nthNewestHashtagTakenAt은_해시태그_성분_행만_센다() {
		repo.insert(brandId, post("TAGONLY", "poster1", NOW.minusSeconds(100)));  // tagged-only — 순위 밖
		repo.upsertHashtag(brandId, post("H1", "poster1", NOW.minusSeconds(1000)), NOW);
		repo.upsertHashtag(brandId, post("H2", "poster1", NOW.minusSeconds(2000)), NOW);
		repo.upsertHashtag(brandId, post("H3", "poster1", NOW.minusSeconds(3000)), NOW);

		assertThat(repo.nthNewestHashtagTakenAt(brandId, 2)).contains(NOW.minusSeconds(2000));
		assertThat(repo.nthNewestHashtagTakenAt(brandId, 4)).isEmpty();   // 3행뿐 — 세트 미포화
		assertThat(repo.nthNewestHashtagTakenAt(brandId, 0)).isEmpty();
	}

	@Test
	void unenumeratedDuePosts_floor는_해시태그만_자르고_direct는_남긴다() {
		repo.upsertHashtag(brandId, post("H_IN", "poster1", NOW.minusSeconds(1000)), NOW);
		repo.upsertHashtag(brandId, post("H_OUT", "poster1", NOW.minusSeconds(5000)), NOW);
		repo.upsertDirect(brandId, post("D_OLD", "poster1", NOW.minusSeconds(9000)), NOW);

		assertThat(repo.unenumeratedDuePosts(brandId, NOW.minusSeconds(86400), NOW.minusSeconds(2000))
				.stream().map(TaggedPostRepository.TrackedPost::shortCode))
				.containsExactly("H_IN", "D_OLD");   // 미보강 우선 동순위 → taken_at DESC
		// null floor = 기존 동작(전부)
		assertThat(repo.unenumeratedDuePosts(brandId, NOW.minusSeconds(86400), null))
				.hasSize(3);
	}

	@Test
	void touchFrozenHashtag은_floor_밖_해시태그_행만_동결_touch한다() {
		repo.upsertHashtag(brandId, post("H_IN", "poster1", NOW.minusSeconds(1000)), NOW);
		repo.upsertHashtag(brandId, post("H_OUT", "poster1", NOW.minusSeconds(5000)), NOW);
		repo.upsertDirect(brandId, post("D_OLD", "poster1", NOW.minusSeconds(9000)), NOW);
		repo.insert(brandId, post("TAGOLD", "poster1", NOW.minusSeconds(9000)));   // tagged-only — 대상 밖

		repo.touchFrozenHashtag(brandId, NOW.minusSeconds(10000), NOW.minusSeconds(2000), NOW);

		assertThat(db.queryForObject("SELECT last_crawled_at FROM brand_tagged_post"
				+ " WHERE brand_id = ? AND short_code = 'H_OUT'", java.sql.Timestamp.class, brandId)
				.toInstant()).isEqualTo(NOW);
		for (String untouched : List.of("H_IN", "D_OLD", "TAGOLD")) {
			assertThat(db.queryForObject("SELECT last_crawled_at IS NULL FROM brand_tagged_post"
					+ " WHERE brand_id = ? AND short_code = ?", Boolean.class, brandId, untouched))
					.as(untouched).isTrue();
		}
		// 되감기 금지 — 더 이른 at으로 재호출해도 유지
		repo.touchFrozenHashtag(brandId, NOW.minusSeconds(10000), NOW.minusSeconds(2000), NOW.minusSeconds(100));
		assertThat(db.queryForObject("SELECT last_crawled_at FROM brand_tagged_post"
				+ " WHERE brand_id = ? AND short_code = 'H_OUT'", java.sql.Timestamp.class, brandId)
				.toInstant()).isEqualTo(NOW);
	}

	/**
	 * minTakenAt 하한(F3, 2026-09-02 최종 리뷰 — touchCrawledDepth의 동형 짝과 같은 유계) — 하한보다
	 * 오래된 hashtag 행은 floor 밖이어도 동결 touch 대상이 아니다(이미 추적 창을 넘어 영구 제외됐다).
	 */
	@Test
	void touchFrozenHashtag은_하한보다_오래된_행은_touch하지_않는다() {
		repo.upsertHashtag(brandId, post("H_OUT", "poster1", NOW.minusSeconds(5000)), NOW);          // 하한 안, floor 밖
		repo.upsertHashtag(brandId, post("H_TOO_OLD", "poster1", NOW.minusSeconds(9000)), NOW);      // 하한보다 오래됨

		repo.touchFrozenHashtag(brandId, NOW.minusSeconds(7000), NOW.minusSeconds(2000), NOW);

		assertThat(db.queryForObject("SELECT last_crawled_at FROM brand_tagged_post"
				+ " WHERE brand_id = ? AND short_code = 'H_OUT'", java.sql.Timestamp.class, brandId)
				.toInstant()).isEqualTo(NOW);
		assertThat(db.queryForObject("SELECT last_crawled_at IS NULL FROM brand_tagged_post"
				+ " WHERE brand_id = ? AND short_code = 'H_TOO_OLD'", Boolean.class, brandId))
				.isTrue();
	}
}
