package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.ad.AdVerdictResult;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BrandPostMetaRepositoryTest {

	JdbcTemplate db;
	BrandPostMetaRepository repo;
	/** 기본 own 브랜드(has_own_link=true) — findUnjudged/countUnjudged의 EXISTS 필터를 통과시킨다. */
	long ownBrandId;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		repo = new BrandPostMetaRepository(db);
		repo.upsert("AAA", "poster1", "FEED", LocalDate.of(2026, 8, 1), "캡션", null, null, null, null);
		ownBrandId = seedBrand("brandx", true);
		linkTaggedPost(ownBrandId, "AAA");
	}

	/**
	 * 브랜드 시드(2026-08-19 경쟁사 판정 제거 설계 §3) — has_own_link 필터 테스트용. brand_post_meta는
	 * 브랜드와 무관한 전역 테이블이라 findUnjudged/countUnjudged가 보려면 brand_tagged_post로 이어줘야
	 * 한다({@link #linkTaggedPost} 참조).
	 */
	long seedBrand(String username, boolean hasOwnLink) {
		return db.queryForObject("""
				INSERT INTO brand_account (username, ig_user_id, status, has_own_link)
				VALUES (?, ?, 'ACTIVE', ?) RETURNING id
				""", Long.class, username, String.valueOf(username.hashCode()), hasOwnLink);
	}

	/** short_code를 브랜드에 태그 링크 — findUnjudged EXISTS 필터가 참조하는 유일한 조인 경로. */
	void linkTaggedPost(long brandId, String shortCode) {
		db.update("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at)
				VALUES (?, ?, 'poster1', ?)
				""", brandId, shortCode, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
	}

	@Test
	void 판정_전_상태는_verdict_null_hash_null() {
		Map<String, BrandPostMetaRepository.AdJudgmentState> state = repo.findAdJudgmentState(List.of("AAA"));
		assertThat(state.get("AAA").adVerdict()).isNull();
		assertThat(state.get("AAA").judgedCaptionHash()).isNull();
	}

	@Test
	void 판정_결과를_기록하면_조회에_반영된다() {
		AdVerdictResult result = new AdVerdictResult("DISCLOSED", "RULE", List.of(),
				List.of(new AdVerdictResult.Evidence("#광고", "CLEAR", 3)), List.of());
		repo.updateAdVerdict("AAA", result, "hash123", Instant.parse("2026-08-17T00:00:00Z"));

		Map<String, BrandPostMetaRepository.AdJudgmentState> state = repo.findAdJudgmentState(List.of("AAA"));
		assertThat(state.get("AAA").adVerdict()).isEqualTo("DISCLOSED");
		assertThat(state.get("AAA").judgedCaptionHash()).isEqualTo("hash123");
		assertThat(db.queryForObject("SELECT ad_verdict_source FROM brand_post_meta WHERE short_code = 'AAA'",
				String.class)).isEqualTo("RULE");
		assertThat(db.queryForObject("SELECT ad_violations::text FROM brand_post_meta WHERE short_code = 'AAA'",
				String.class)).isEqualTo("[]");
		assertThat(db.queryForObject("SELECT ad_evidence::text FROM brand_post_meta WHERE short_code = 'AAA'",
				String.class)).contains("#광고");
	}

	/**
	 * S3 — self(embed)는 is_paid_partnership을 취득할 수단이 없어 항상 null을 넘긴다. 과거엔
	 * EXCLUDED로 무조건 덮어써서, Hiker가 먼저 관측한 협찬 판정(광고 판정 Tier0 최우선 신호,
	 * AdDisclosureJudgeService)이 같은 날 self 재수집에 지워졌다 — COALESCE로 보존한다.
	 */
	@Test
	void is_paid_partnership_null_관측은_기존_값을_보존한다() {
		repo.upsert("AAA", "poster1", "REELS", LocalDate.of(2026, 8, 1), "캡션", null,
				"https://video.example/a.mp4", 10.0, true);

		// self 재수집 — is_paid_partnership 구조적으로 항상 null
		repo.upsert("AAA", "poster1", "REELS", LocalDate.of(2026, 8, 1), "캡션", null,
				"https://video.example/a.mp4", 10.0, null);

		Boolean isPaidPartnership = db.queryForObject(
				"SELECT is_paid_partnership FROM brand_post_meta WHERE short_code='AAA'", Boolean.class);
		assertThat(isPaidPartnership).isTrue();
	}

	/**
	 * S4 — self(embed·feed/user)는 콘텐츠 타입을 구조적으로 확정 못 하면 null을 넘긴다
	 * (EmbedPostFetcher·FeedUserPostsFetcher 참조). Hiker가 이미 REELS/FEED를 확정 저장한 행을
	 * self의 미확정 null 재수집이 강등 덮어쓰기하면 안 된다 — is_paid_partnership과 동일한
	 * COALESCE 보호(S3 수정과 동형).
	 */
	@Test
	void content_type_null_수집은_기존_content_type을_보존한다() {
		repo.upsert("AAA", "poster1", "REELS", LocalDate.of(2026, 8, 1), "캡션", null, null, null, null);

		// self 재수집 — content_type 구조적으로 확정 불가(null)
		repo.upsert("AAA", "poster1", null, LocalDate.of(2026, 8, 1), "캡션 갱신", null, null, null, null);

		String contentType = db.queryForObject(
				"SELECT content_type FROM brand_post_meta WHERE short_code='AAA'", String.class);
		assertThat(contentType).isEqualTo("REELS");
	}

	@Test
	void 빈_코드_목록은_빈_맵() {
		assertThat(repo.findAdJudgmentState(List.of())).isEmpty();
	}

	@Test
	void 미존재_short_code는_상태가_null_state_자체는_존재() {
		// findAdJudgmentState는 존재하는 short_code만 맵에 담는다(호출부가 null-state를 "미존재"로 취급)
		Map<String, BrandPostMetaRepository.AdJudgmentState> state = repo.findAdJudgmentState(List.of("ZZZ"));
		assertThat(state).doesNotContainKey("ZZZ");
	}

	@Test
	void 미존재_short_code에_판정_기록은_무해하다() {
		// 0-row UPDATE — 예외 없이 통과(경고 로그만 남기고 호출부 분기 없음, 다음 스윕이 자연 복구)
		AdVerdictResult result = new AdVerdictResult("DISCLOSED", "RULE", List.of(), List.of(), List.of());
		repo.updateAdVerdict("ZZZ", result, "hash999", Instant.parse("2026-08-17T00:00:00Z"));

		assertThat(repo.findAdJudgmentState(List.of("ZZZ"))).doesNotContainKey("ZZZ");
	}

	@Test
	void findUnjudged는_ad_verdict가_NULL인_행만_반환한다() {
		repo.upsert("BBB", "poster1", "REELS", LocalDate.of(2026, 8, 2), "다른 캡션", null,
				"https://video.example/b.mp4", 12.5, true);
		AdVerdictResult result = new AdVerdictResult("DISCLOSED", "RULE", List.of(), List.of(), List.of());
		repo.updateAdVerdict("BBB", result, "hashBBB", Instant.parse("2026-08-17T00:00:00Z"));

		List<BrandPostMetaRepository.UnjudgedPost> unjudged = repo.findUnjudged(10);

		assertThat(unjudged).extracting(BrandPostMetaRepository.UnjudgedPost::shortCode).containsExactly("AAA");
		BrandPostMetaRepository.UnjudgedPost aaa = unjudged.get(0);
		assertThat(aaa.caption()).isEqualTo("캡션");
		assertThat(aaa.contentType()).isEqualTo("FEED");
		assertThat(aaa.videoUrl()).isNull();
		assertThat(aaa.isPaidPartnership()).isNull();
	}

	@Test
	void findUnjudged는_limit을_넘지_않는다() {
		repo.upsert("BBB", "poster1", "FEED", LocalDate.of(2026, 8, 2), "캡션2", null, null, null, null);
		repo.upsert("CCC", "poster1", "FEED", LocalDate.of(2026, 8, 3), "캡션3", null, null, null, null);
		linkTaggedPost(ownBrandId, "BBB");
		linkTaggedPost(ownBrandId, "CCC");

		assertThat(repo.findUnjudged(2)).hasSize(2);
	}

	// ---------- has_own_link 필터(2026-08-19 경쟁사 판정 제거 설계 §3) ----------

	/** 활성 own 연결이 없는 브랜드에만 태그된 게시물은 후보에서 빠진다(백필 스킵). */
	@Test
	void findUnjudged는_competitor_전용_브랜드_게시물을_제외한다() {
		long competitorBrandId = seedBrand("rival", false);
		repo.upsert("RIVAL1", "poster2", "FEED", LocalDate.of(2026, 8, 2), "경쟁사 캡션", null, null, null, null);
		linkTaggedPost(competitorBrandId, "RIVAL1");

		List<BrandPostMetaRepository.UnjudgedPost> unjudged = repo.findUnjudged(10);

		assertThat(unjudged).extracting(BrandPostMetaRepository.UnjudgedPost::shortCode).containsExactly("AAA");
	}

	/** own 연결이 하나라도 있는 브랜드에 걸쳐 태그된 게시물(겹침)은 그대로 후보다. */
	@Test
	void findUnjudged는_own과_경쟁사에_동시_태그된_게시물을_포함한다() {
		long competitorBrandId = seedBrand("rival", false);
		repo.upsert("SHARED1", "poster2", "FEED", LocalDate.of(2026, 8, 2), "공유 캡션", null, null, null, null);
		linkTaggedPost(competitorBrandId, "SHARED1");
		linkTaggedPost(ownBrandId, "SHARED1");   // 같은 게시물이 own 브랜드에도 태그됨

		List<BrandPostMetaRepository.UnjudgedPost> unjudged = repo.findUnjudged(10);

		assertThat(unjudged).extracting(BrandPostMetaRepository.UnjudgedPost::shortCode)
				.containsExactlyInAnyOrder("AAA", "SHARED1");
	}

	@Test
	void countUnjudged는_competitor_전용_브랜드_게시물을_제외한다() {
		long competitorBrandId = seedBrand("rival", false);
		repo.upsert("RIVAL1", "poster2", "FEED", LocalDate.of(2026, 8, 2), "경쟁사 캡션", null, null, null, null);
		linkTaggedPost(competitorBrandId, "RIVAL1");

		assertThat(repo.countUnjudged()).isEqualTo(1);   // AAA만
	}

	@Test
	void countUnjudged는_ad_verdict_NULL_전체_건수() {
		repo.upsert("BBB", "poster1", "FEED", LocalDate.of(2026, 8, 2), "캡션2", null, null, null, null);
		AdVerdictResult result = new AdVerdictResult("DISCLOSED", "RULE", List.of(), List.of(), List.of());
		repo.updateAdVerdict("BBB", result, "hashBBB", Instant.parse("2026-08-17T00:00:00Z"));

		assertThat(repo.countUnjudged()).isEqualTo(1);   // AAA만 미판정
	}
}
