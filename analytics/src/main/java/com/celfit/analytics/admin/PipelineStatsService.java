package com.celfit.analytics.admin;

import com.celfit.analytics.config.AnalyticsSettings;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 퍼널 숫자 — 빠른 집계(단순 카운트, 매 요청)와 무거운 집계(후보 뷰 스캔 — 운영 실측 3.5분,
 * 비동기 + TTL 캐시)를 분리한다. PR #45의 비용 카드 캐시 패턴 승계 (JobCostEstimator는 본 서비스로 대체·삭제).
 */
public class PipelineStatsService {

	private static final Logger log = LoggerFactory.getLogger(PipelineStatsService.class);
	private static final java.time.Duration TTL = java.time.Duration.ofMinutes(30);

	/** candidates가 -1이면 아직 집계 전("집계 중…" 표시). */
	public record Funnel(long rawContents, long candidates, long analyzed, long served,
			long copiedAccounts, long beautyAccounts, int todayPlanned, int daysToFull,
			Instant heavyComputedAt) {
	}

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final AnalyticsSettings settings;

	private volatile long cachedCandidates = -1;
	private volatile Instant heavyComputedAt;
	private final AtomicBoolean computing = new AtomicBoolean();

	public PipelineStatsService(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			AnalyticsSettings settings) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.settings = settings;
	}

	public Funnel funnel() {
		refreshHeavyIfStale();
		long rawContents = count(raw, "SELECT count(*) FROM content");
		long analyzed = count(analysis, "SELECT count(*) FROM content_analyses");
		long served = count(analysis, "SELECT count(*) FROM contents");
		long copied = count(analysis, "SELECT count(DISTINCT handle) FROM account_analyses");
		long beauty = count(analysis, "SELECT count(*) FROM accounts");
		long candidates = cachedCandidates;
		int limit = settings.analyzeBatchLimit();
		return new Funnel(rawContents, candidates, analyzed, served, copied, beauty,
				candidates < 0 ? 0 : todayPlanned(candidates, analyzed, limit),
				candidates < 0 ? 0 : daysToFull(candidates, analyzed, limit),
				heavyComputedAt);
	}

	static int todayPlanned(long candidates, long analyzed, int batchLimit) {
		long remaining = Math.max(0, candidates - analyzed);
		return (int) Math.min(remaining, batchLimit);
	}

	static int daysToFull(long candidates, long analyzed, int batchLimit) {
		long remaining = Math.max(0, candidates - analyzed);
		if (remaining == 0 || batchLimit <= 0) return 0;
		return (int) Math.ceilDiv(remaining, batchLimit);
	}

	private void refreshHeavyIfStale() {
		boolean stale = heavyComputedAt == null
				|| Instant.now().isAfter(heavyComputedAt.plus(TTL));
		if (stale && computing.compareAndSet(false, true)) {
			Thread.ofVirtual().name("pipeline-stats").start(() -> {
				try {
					cachedCandidates = count(raw, "SELECT count(*) FROM analytics.v_analysis_candidates");
					heavyComputedAt = Instant.now();
				} catch (RuntimeException e) {
					log.warn("후보 집계 실패 — 이전 캐시 유지", e);
				} finally {
					computing.set(false);
				}
			});
		}
	}

	private static long count(JdbcTemplate t, String sql) {
		Long v = t.queryForObject(sql, Long.class);
		return v == null ? 0 : v;
	}
}
