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
	void findUnjudged는_ad_verdict가_NULL인_행을_반환한다() {
		repo.upsert("BBB", "poster1", "REELS", LocalDate.of(2026, 8, 2), "다른 캡션", null,
				"https://video.example/b.mp4", 12.5, true);
		AdVerdictResult result = new AdVerdictResult("DISCLOSED", "RULE", List.of(), List.of(), List.of());
		// BBB는 실제 caption의 md5로 기록해 해시가 정합한다(드리프트 없음) — 아래 드리프트
		// 전용 테스트와 구분하기 위해 "hashBBB" 같은 임의 문자열 대신 진짜 md5를 쓴다.
		repo.updateAdVerdict("BBB", result, md5("다른 캡션"), Instant.parse("2026-08-17T00:00:00Z"));

		List<BrandPostMetaRepository.UnjudgedPost> unjudged = repo.findUnjudged(10);

		assertThat(unjudged).extracting(BrandPostMetaRepository.UnjudgedPost::shortCode).containsExactly("AAA");
		BrandPostMetaRepository.UnjudgedPost aaa = unjudged.get(0);
		assertThat(aaa.caption()).isEqualTo("캡션");
		assertThat(aaa.contentType()).isEqualTo("FEED");
		assertThat(aaa.videoUrl()).isNull();
		assertThat(aaa.isPaidPartnership()).isNull();
	}

	// ---------- 백필 드리프트 갭 폐쇄(2026-08-28) — judged_caption_hash 불일치 행도 대상 ----------

	@Test
	void findUnjudged는_verdict_있어도_caption_해시가_불일치하면_대상에_포함한다() {
		// 08-28: ad_verdict IS NULL만 보던 findUnjudged가 (a) 스윕이 캡션을 갱신했지만 180일 추적
		// 창 밖이라 재판정이 안 걸린 행, (b) LLM 실패로 verdict는 남고 해시만 낡은 행을 영구
		// 방치했다. AAA를 먼저 판정(당시 캡션 기준 정합 해시)한 뒤 caption만 갱신하면(upsert),
		// judged_caption_hash가 새 caption과 불일치해 다시 대상이어야 한다.
		AdVerdictResult result = new AdVerdictResult("NOT_DISCLOSED", "RULE", List.of("NO_DISCLOSURE"), List.of(),
				List.of());
		repo.updateAdVerdict("AAA", result, md5("캡션"), Instant.parse("2026-08-17T00:00:00Z"));
		repo.upsert("AAA", "poster1", "FEED", LocalDate.of(2026, 8, 1), "캡션이 갱신됐습니다", null, null, null, null);

		List<BrandPostMetaRepository.UnjudgedPost> unjudged = repo.findUnjudged(10);

		assertThat(unjudged).extracting(BrandPostMetaRepository.UnjudgedPost::shortCode).containsExactly("AAA");
		assertThat(unjudged.get(0).caption()).isEqualTo("캡션이 갱신됐습니다");
	}

	@Test
	void findUnjudged는_verdict_있고_caption_해시가_일치하면_대상에서_제외한다() {
		// 판정 이후 캡션이 안 바뀌었으면(해시 정합) 재판정 불필요 — 드리프트 갭 폐쇄가 과포함으로
		// 번지지 않았는지 확인.
		AdVerdictResult result = new AdVerdictResult("DISCLOSED", "RULE", List.of(), List.of(), List.of());
		repo.updateAdVerdict("AAA", result, md5("캡션"), Instant.parse("2026-08-17T00:00:00Z"));

		assertThat(repo.findUnjudged(10)).isEmpty();
	}

	@Test
	void countUnjudged는_caption_해시_불일치_행도_카운트한다() {
		// findUnjudged와 카운트 조건이 어긋나면 AdDisclosureJudgeService.backfillUnjudged가
		// initialRemaining==0으로 오판해 드리프트 행이 있어도 백필을 아예 시작하지 않는다 —
		// 두 메서드의 대상 조건이 반드시 동일해야 하는 이유(각 메서드 javadoc 참조).
		AdVerdictResult result = new AdVerdictResult("NOT_DISCLOSED", "RULE", List.of("NO_DISCLOSURE"), List.of(),
				List.of());
		repo.updateAdVerdict("AAA", result, md5("캡션"), Instant.parse("2026-08-17T00:00:00Z"));
		repo.upsert("AAA", "poster1", "FEED", LocalDate.of(2026, 8, 1), "캡션이 갱신됐습니다", null, null, null, null);

		assertThat(repo.countUnjudged()).isEqualTo(1);
	}

	private static String md5(String s) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("MD5")
					.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
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
		// BBB는 실제 caption("캡션2")의 md5로 기록해 해시가 정합한다 — 드리프트 갭 폐쇄(08-28) 이후
		// countUnjudged가 해시 불일치도 세므로, 임의 문자열("hashBBB")을 쓰면 BBB도 불일치로
		// 잡혀 이 테스트가 검증하려는 "AAA만 미판정" 전제가 깨진다.
		repo.updateAdVerdict("BBB", result, md5("캡션2"), Instant.parse("2026-08-17T00:00:00Z"));

		assertThat(repo.countUnjudged()).isEqualTo(1);   // AAA만 미판정
	}
}
