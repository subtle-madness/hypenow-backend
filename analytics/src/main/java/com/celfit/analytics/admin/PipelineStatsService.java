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
	 * 계산의 분자는 이것. backfillAnalyzed는 late_backfill(상세 전용, 랭킹 제외).
	 * analyzed(전체)−timely−backfill = immature/레거시(가드 도입 전 기분석분).
	 * accountTarget은 계정 카피 stale+쿨다운 대상 수(AccountAnalysisJob 쿼리의 카운트판).
	 */
	public record Funnel(long rawContents, long candidates, long timelyExcluded,
			long analyzed, long timelyAnalyzed, long backfillAnalyzed, long served,
			long copiedAccounts, long beautyAccounts, long accountTarget,
			int todayPlanned, int daysToFull,
			int pinDays, int slackDays, Instant heavyComputedAt,
			String candidatesError) {
	}

	/**
	 * 파이프라인 건강·처리량 — 전부 싼 쿼리(인메모리 RunHistory와 달리 재시작에도 영속).
	 * last*At은 각 산출물의 최신 시각(신선도), today*는 오늘(KST) 처리량.
	 * lastMirrorAt은 미러된 지표 스냅샷 최신(captured_at) — 미러 잡 자체 시각이 아닌 신선도 프록시.
	 */
	public record Health(long todayAnalyzed, long todayAccountCopied,
			Instant lastAnalysisAt, Instant lastMirrorAt, Instant lastAccountCopyAt) {
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
	/** 마지막 후보 집계 시도의 실패 사유 — 성공하면 null. "집계 중"과 "집계 실패"를 UI에서 가르는 신호.
	 *  (뷰 드리프트로 후보 뷰가 사라지면 영원히 "집계 중"으로 보이던 문제 — 2026-07-21) */
	private volatile String heavyError;
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
		// content_analyses 집계 1패스 — 전체·timely·late_backfill을 한 번에 (개별 count 반복 제거).
		java.util.Map<String, Object> ca = analysis.queryForMap("""
				SELECT count(*) AS total,
				       count(*) FILTER (WHERE metric_timeliness = 'timely')        AS timely,
				       count(*) FILTER (WHERE metric_timeliness = 'late_backfill') AS backfill
				FROM content_analyses""");
		long analyzed = num(ca.get("total"));
		long timelyAnalyzed = num(ca.get("timely"));
		long backfillAnalyzed = num(ca.get("backfill"));
		long served = count(analysis, "SELECT count(*) FROM contents");
		long copied = count(analysis, "SELECT count(DISTINCT handle) FROM account_analyses");
		long beauty = count(analysis, "SELECT count(*) FROM accounts");
		long accountTarget = accountTarget();
		long candidates = cachedCandidates;
		int limit = settings.analyzeBatchLimit();
		// 잔여 계산의 분자는 timely만 — 가드 밖 기분석분은 후보 풀 밖이라 섞으면 잔여가 과소된다.
		return new Funnel(rawContents, candidates, cachedTimelyExcluded, analyzed, timelyAnalyzed,
				backfillAnalyzed, served, copied, beauty, accountTarget,
				candidates < 0 ? 0 : todayPlanned(candidates, timelyAnalyzed, limit),
				candidates < 0 ? 0 : daysToFull(candidates, timelyAnalyzed, limit),
				settings.metricPinDays(), settings.analyzeTimelySlackDays(),
				heavyComputedAt, heavyError);
	}

	/** 근본 원인 한 줄 — SQL 예외는 메시지가 길어 UI엔 첫 줄만(뷰 부재 등 원인이 여기 담긴다). */
	static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		String m = t.getMessage();
		if (m == null || m.isBlank()) {
			return t.getClass().getSimpleName();
		}
		String firstLine = m.lines().findFirst().orElse(m).trim();
		return firstLine.length() > 160 ? firstLine.substring(0, 160) + "…" : firstLine;
	}

	/**
	 * 파이프라인 건강·오늘 처리량 — analyzed_at(V3, DEFAULT now()) 기반. 오늘 경계는 KST 자정.
	 * timestamptz를 Asia/Seoul 로컬로 변환해 KST 날짜와 비교한다(운영 컨테이너 존=UTC 무관).
	 */
	public Health health() {
		java.util.Map<String, Object> c = analysis.queryForMap("""
				SELECT max(analyzed_at) AS last_at,
				       count(*) FILTER (WHERE analyzed_at AT TIME ZONE 'Asia/Seoul'
				                        >= (now() AT TIME ZONE 'Asia/Seoul')::date) AS today
				FROM content_analyses""");
		java.util.Map<String, Object> a = analysis.queryForMap("""
				SELECT max(analyzed_at) AS last_at,
				       count(*) FILTER (WHERE analyzed_at AT TIME ZONE 'Asia/Seoul'
				                        >= (now() AT TIME ZONE 'Asia/Seoul')::date) AS today
				FROM account_analyses""");
		Instant lastMirror = instant(analysis,
				"SELECT max(captured_at) FROM content_metric_snapshots");
		return new Health(num(c.get("today")), num(a.get("today")),
				toInstant(c.get("last_at")), lastMirror, toInstant(a.get("last_at")));
	}

	/** 계정 카피 대상 수 — AccountAnalysisJob.run()의 대상 쿼리를 count로. */
	private long accountTarget() {
		Long v = analysis.queryForObject("""
				SELECT count(*)
				FROM account_summaries s
				LEFT JOIN LATERAL (
				  SELECT a.input_last_posted_at, a.analyzed_at
				  FROM account_analyses a WHERE a.handle = s.handle
				  ORDER BY a.analyzed_at DESC LIMIT 1
				) latest ON true
				WHERE latest.analyzed_at IS NULL
				   OR (latest.input_last_posted_at IS DISTINCT FROM s.last_posted_at
				       AND latest.analyzed_at < now() - make_interval(days => ?))""",
				Long.class, settings.accountAnalyzeCooldownDays());
		return v == null ? 0 : v;
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
					heavyError = null;
				} catch (RuntimeException e) {
					// 사유를 남긴다 — 캐시가 없으면 UI가 "집계 중"이 아니라 "집계 실패"로 표시해야 한다.
					heavyError = rootMessage(e);
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

	private static Instant instant(JdbcTemplate t, String sql) {
		return toInstant(t.queryForObject(sql, java.sql.Timestamp.class));
	}

	/** FILTER count 등 numeric/bigint 혼재 대응 — Number로 안전 변환. */
	private static long num(Object v) {
		return v == null ? 0 : ((Number) v).longValue();
	}

	/** timestamptz(JDBC Timestamp/OffsetDateTime) → Instant. NULL이면 null. */
	private static Instant toInstant(Object v) {
		return switch (v) {
			case null -> null;
			case java.sql.Timestamp ts -> ts.toInstant();
			case java.time.OffsetDateTime odt -> odt.toInstant();
			case Instant i -> i;
			default -> throw new IllegalStateException("예상 못한 시각 타입: " + v.getClass());
		};
	}
}
