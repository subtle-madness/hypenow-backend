package com.celfit.analytics.mirror;

import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 발굴 사전집계 matview 갱신(V20260827045100 3종) — 입력(account_content_series·
 * content_analyses)을 쓰는 잡(MIRROR·ANALYZE·LATE_BACKFILL_ANALYZE·BATCH_COLLECT) 완료 후
 * AnalyticsJobService가 호출한다. CONCURRENTLY라 갱신 중에도 was 조회가 막히지 않는다
 * (unique index 필수 — 마이그레이션이 보장).
 */
public class DerivedViewRefresher {

	private static final Logger log = LoggerFactory.getLogger(DerivedViewRefresher.class);

	private static final List<String> MATVIEWS = List.of(
			"account_beauty_ratio", "account_category_share", "account_sponsored_counts");

	private final JdbcTemplate analysis;

	public DerivedViewRefresher(DataSource analysisDataSource) {
		this.analysis = new JdbcTemplate(analysisDataSource);
	}

	public void refresh() {
		for (String view : MATVIEWS) {
			long start = System.nanoTime();
			analysis.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view);
			log.info("파생 matview 갱신 {} ({}ms)", view, (System.nanoTime() - start) / 1_000_000);
		}
	}
}
