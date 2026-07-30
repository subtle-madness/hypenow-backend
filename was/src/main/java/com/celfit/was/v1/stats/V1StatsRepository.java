package com.celfit.was.v1.stats;

import com.celfit.contract.analysis.LandingStats;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 6.20 랜딩 통계 조회 — analysis DB 미러(landing_stats) 읽기 전용. */
@Repository
public class V1StatsRepository {

	private final JdbcClient jdbcClient;

	public V1StatsRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 랜딩 통계 1행(미러). 미러 미실행이면 empty → 컨트롤러가 404.
	 * followers3k10k 등은 언더스코어가 없는 이름이 정본 — 미러의 toSnakeCase 변환 결과와 일치(§4-3).
	 * followers500to3k COALESCE: V47 expand 직후 다음 미러 실행까지 NULL인 창을 0으로 방어.
	 */
	public Optional<LandingStats> find() {
		return jdbcClient.sql("""
				SELECT contents_count, influencers_count, total_views, avg_views,
				       followers3k10k, followers10k30k, followers30k50k, updated_at,
				       COALESCE(followers500to3k, 0) AS followers500to3k
				FROM landing_stats
				LIMIT 1
				""").query(LandingStats.class).optional();
	}
}
