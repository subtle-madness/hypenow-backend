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
}
