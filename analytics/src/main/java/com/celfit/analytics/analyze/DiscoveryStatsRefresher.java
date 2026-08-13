package com.celfit.analytics.analyze;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * account_discovery_stats 물화 뷰 갱신 (설계 2026-08-13-discovery-stats-matview-design).
 * was 발굴 표면이 요청마다 치던 전 계정 집계를 스냅샷으로 대체했으므로, 입력이 변하는 잡
 * (미러·콘텐츠 분석·늦크롤 백필·배치 수거·버스트 collect) 종료 시점마다 여기로 갱신한다.
 *
 * <p>CONCURRENTLY: 갱신 중에도 was 조회가 이전 스냅샷을 계속 읽는다(서빙 무중단) —
 * 마이그레이션의 유니크 인덱스가 전제 조건. 갱신 실패는 잡 결과를 오염시키지 않고 삼킨다
 * (스냅샷이 한 사이클 낡을 뿐이고, 다음 잡 종료 때 자연 회복된다).
 */
public class DiscoveryStatsRefresher {

	private static final Logger log = LoggerFactory.getLogger(DiscoveryStatsRefresher.class);

	private final JdbcTemplate analysis;

	public DiscoveryStatsRefresher(DataSource analysisDataSource) {
		this.analysis = new JdbcTemplate(analysisDataSource);
	}

	public void refresh() {
		try {
			long started = System.currentTimeMillis();
			analysis.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY account_discovery_stats");
			log.info("account_discovery_stats 갱신 완료 ({}ms)", System.currentTimeMillis() - started);
		} catch (Exception e) {
			log.error("account_discovery_stats 갱신 실패 — 스냅샷은 다음 잡 종료 때 재시도된다", e);
		}
	}
}
