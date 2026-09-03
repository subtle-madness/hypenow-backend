package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * app.brand_hashtag_seed 접점(2026-09-03 자동 시드 재설계 §4-1) — BrandHashtagTagRepositoryTest와
 * 같은 통합 관용구. 브랜드당 1행 계약(동시 호출도 1행)과 SKIP(tag NULL) 저장을 실 왕복으로 고정한다.
 */
class BrandHashtagSeedRepositoryTest extends IntegrationTest {

	@Autowired
	BrandHashtagSeedRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	// brand_id는 테스트마다 고유해야 한다 — 통합 테스트가 컨테이너를 공유하고 롤백이 없다.
	long brandId;

	@BeforeEach
	void 브랜드_식별자() {
		brandId = System.nanoTime();
	}

	@Test
	void 없으면_empty다() {
		assertThat(repository.find(brandId)).isEmpty();
	}

	@Test
	void 삽입_후_조회된다() {
		repository.insertIgnore(brandId, "FREQ", "닥피");

		assertThat(repository.find(brandId)).hasValueSatisfying(row -> {
			assertThat(row.brandId()).isEqualTo(brandId);
			assertThat(row.path()).isEqualTo("FREQ");
			assertThat(row.tag()).isEqualTo("닥피");
			assertThat(row.seededAt()).isNotNull();
		});
	}

	@Test
	void SKIP은_tag가_null이다() {
		repository.insertIgnore(brandId, "SKIP", null);

		assertThat(repository.find(brandId)).hasValueSatisfying(row -> {
			assertThat(row.path()).isEqualTo("SKIP");
			assertThat(row.tag()).isNull();
		});
	}

	/** 동시 호출 경합 — 두 번째 삽입은 조용히 무시되고 첫 값이 남는다(브랜드당 1회 계약). */
	@Test
	void 재삽입은_무시되고_첫_값이_남는다() {
		repository.insertIgnore(brandId, "FREQ", "첫값");
		repository.insertIgnore(brandId, "AI", "둘째값");

		assertThat(repository.find(brandId)).hasValueSatisfying(row -> {
			assertThat(row.path()).isEqualTo("FREQ");
			assertThat(row.tag()).isEqualTo("첫값");
		});
	}

	@Test
	void 다른_브랜드는_영향이_없다() {
		repository.insertIgnore(brandId, "FREQ", "내태그");

		assertThat(repository.find(brandId + 1)).isEmpty();
	}

	// ---------- 링크 표식(brand_monitorings.hashtag_seeded_at) ----------

	@Autowired
	BrandLinkRepository linkRepository;

	@Test
	void markHashtagSeeded는_링크_행에_시각을_찍는다() {
		long userId = jdbcClient
				.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "seed-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		long linkId = linkRepository.insertLink(userId, brandId, "brandx", "own", 12);
		assertThat(linkRepository.findActiveByUserAndBrand(userId, brandId))
				.hasValueSatisfying(link -> assertThat(link.hashtagSeededAt()).isNull());

		linkRepository.markHashtagSeeded(linkId);

		assertThat(linkRepository.findActiveByUserAndBrand(userId, brandId))
				.hasValueSatisfying(link -> assertThat(link.hashtagSeededAt()).isNotNull());
	}
}
