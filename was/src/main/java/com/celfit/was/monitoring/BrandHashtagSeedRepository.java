package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 브랜드 해시태그 자동 시드 기록(2026-09-03 자동 시드 재설계 §4-1) — app.brand_hashtag_seed.
 * <b>브랜드당 1행</b>이 계약이다: monitoring 제안 계산(AI 콜 포함)을 브랜드 생애 1회로 묶고,
 * 두 번째 사용자가 같은 브랜드에 링크하면 계산 없이 이 행의 태그를 자기 장부에 복사한다.
 *
 * <p>{@code brand_id}는 monitoring {@code brand_account.id} 논리 참조다(크로스 DB FK 없음 —
 * {@code BrandLinkRepository}와 같은 관용구).
 */
@Repository
public class BrandHashtagSeedRepository {

	/** 시드 1행. path가 SKIP이면 tag는 null이다(이미 사용자 태그가 있어 아무것도 심지 않은 브랜드). */
	public record SeedRow(long brandId, String path, String tag, OffsetDateTime seededAt) {
	}

	private final JdbcClient jdbcClient;

	public BrandHashtagSeedRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<SeedRow> find(long brandId) {
		return jdbcClient.sql("""
				SELECT brand_id, path, tag, seeded_at FROM app.brand_hashtag_seed WHERE brand_id = :brandId
				""")
				.param("brandId", brandId)
				.query(SeedRow.class)
				.optional();
	}

	/**
	 * 시드 기록 — 이미 있으면 조용히 무시한다(ON CONFLICT DO NOTHING). 두 조회가 동시에 같은
	 * 브랜드를 계산해도 먼저 커밋한 쪽이 이기고, 진 쪽은 호출부가 재조회해 그 값을 쓴다
	 * (호출부 {@code V1BrandAccountService.ensureAutoSeeded}의 "INSERT 후 재조회" 관용구).
	 *
	 * @param tag SKIP이면 null.
	 */
	public void insertIgnore(long brandId, String path, String tag) {
		jdbcClient.sql("""
				INSERT INTO app.brand_hashtag_seed (brand_id, path, tag)
				VALUES (:brandId, :path, :tag)
				ON CONFLICT (brand_id) DO NOTHING
				""")
				.param("brandId", brandId)
				.param("path", path)
				.param("tag", tag)
				.update();
	}
}
