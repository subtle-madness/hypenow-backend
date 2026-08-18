package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.ad.AdVerdictResult;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BrandPostMetaRepositoryTest {

	JdbcTemplate db;
	BrandPostMetaRepository repo;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		repo = new BrandPostMetaRepository(db);
		repo.upsert("AAA", "poster1", "FEED", LocalDate.of(2026, 8, 1), "캡션", null, null, null, null);
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

		assertThat(repo.findUnjudged(2)).hasSize(2);
	}

	@Test
	void countUnjudged는_ad_verdict_NULL_전체_건수() {
		repo.upsert("BBB", "poster1", "FEED", LocalDate.of(2026, 8, 2), "캡션2", null, null, null, null);
		AdVerdictResult result = new AdVerdictResult("DISCLOSED", "RULE", List.of(), List.of(), List.of());
		repo.updateAdVerdict("BBB", result, "hashBBB", Instant.parse("2026-08-17T00:00:00Z"));

		assertThat(repo.countUnjudged()).isEqualTo(1);   // AAA만 미판정
	}
}
