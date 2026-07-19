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

	/**
	 * candidates·timelyExcluded가 -1이면 아직 집계 전("집계 중…" 표시).
	 * timelyAnalyzed는 metric_timeliness='timely'(V33)만 — 후보 풀의 부분집합이라 커버리지·잔여
	 * 계산의 분자는 이것. analyzed(전체)에는 가드 도입 전 기분석분(백필·미성숙)이 섞여 있다.
	 */
	public record Funnel(long rawContents, long candidates, long timelyExcluded,
			long analyzed, long timelyAnalyzed, long served,
			long copiedAccounts, long beautyAccounts, int todayPlanned, int daysToFull,
			int pinDays, int slackDays, Instant heavyComputedAt) {
	}

	/**
	 * 무거운 집계 — 후보(04 뷰)와 "성숙 풀"(04 뷰 자격에서 제때 크롤 가드만 뺀 모수: 뷰티 서빙 ∩
	 * 캡션 ∩ 숙성)을 한 문장으로 읽는다. 차이가 곧 가드 제외분(대부분 늦크롤 백필 — PR #49의
	 * MVP 제외 정책)이라 퍼널의 수집→후보 낙차를 설명하는 수치가 된다. 두 카운트를 쪼개면
	 * 다른 시점의 값을 빼게 되므로 반드시 한 statement로.
	 */
	private static final String HEAVY_SQL = """
			SELECT
			  (SELECT count(*) FROM analytics.v_analysis_candidates) AS candidates,
			  (SELECT count(*) FROM analytics.v_contents v
			     WHERE v.caption IS NOT NULL AND btrim(v.caption) <> ''
			       AND v.posted_at + make_interval(days => COALESCE(
			             (SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-maturity-days'), 3)) <= now()
			  ) AS mature_pool
			""";

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final AnalyticsSettings settings;

	private volatile long cachedCandidates = -1;
	private volatile long cachedTimelyExcluded = -1;
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
		long timelyAnalyzed = count(analysis,
				"SELECT count(*) FROM content_analyses WHERE metric_timeliness = 'timely'");
		long served = count(analysis, "SELECT count(*) FROM contents");
		long copied = count(analysis, "SELECT count(DISTINCT handle) FROM account_analyses");
		long beauty = count(analysis, "SELECT count(*) FROM accounts");
		long candidates = cachedCandidates;
		int limit = settings.analyzeBatchLimit();
		// 잔여 계산의 분자는 timely만 — 가드 밖 기분석분은 후보 풀 밖이라 섞으면 잔여가 과소된다.
		return new Funnel(rawContents, candidates, cachedTimelyExcluded, analyzed, timelyAnalyzed,
				served, copied, beauty,
				candidates < 0 ? 0 : todayPlanned(candidates, timelyAnalyzed, limit),
				candidates < 0 ? 0 : daysToFull(candidates, timelyAnalyzed, limit),
				settings.metricPinDays(), settings.analyzeTimelySlackDays(),
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
					java.util.Map<String, Object> row = raw.queryForMap(HEAVY_SQL);
					long candidates = ((Number) row.get("candidates")).longValue();
					long maturePool = ((Number) row.get("mature_pool")).longValue();
					cachedCandidates = candidates;
					cachedTimelyExcluded = Math.max(0, maturePool - candidates);
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
