package com.celfit.analytics.admin;

import com.celfit.analytics.config.AnalyticsSettings;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 대시보드 숫자 — 빠른 집계(단순 카운트, 매 요청)와 무거운 집계(후보·서빙 뷰 스캔 + 크로스 DB 대조,
 * 비동기 + TTL 캐시)를 분리한다. PR #45의 비용 카드 캐시 패턴 승계.
 *
 * <p>v3 재설계(2026-07-21, plans/2026-07-21-analytics-dashboard-v3-data-model.md): 퍼널 폐기 →
 * 계정 축·콘텐츠 축 + 상태 전이. 핵심은 G1(크로스 DB 잔여 대조 — 후보 ∩ 미분석을 아무도 계산 안 하던
 * 갭)과 G2(커버리지 분모·분자를 "현재 서빙 모수"로 재정의 — 누적 분석 수는 모수 리비전이 섞여 각주로
 * 강등). 대조는 ContentAnalysisJob이 이미 하는 패턴(analysis short_code 셋 로드 → raw 후보와 Java
 * 대조)을 무거운 집계에 편입한 것.
 */
public class PipelineStatsService {

	private static final Logger log = LoggerFactory.getLogger(PipelineStatsService.class);
	private static final java.time.Duration TTL = java.time.Duration.ofMinutes(30);

	/**
	 * 무거운 집계 스냅샷 (G1·G2) — raw 쪽 수치는 REPEATABLE READ 한 트랜잭션에서 읽어 서로 정합.
	 * analysis 쪽 셋(기분석 short_code·카피 handle)은 다른 DB라 스냅샷 정합이 원리적으로 불가 —
	 * 집계 시각 표기로 보완한다(후보 뷰는 판정 진행 중엔 분 단위로 변한다).
	 *
	 * <p>트랙 구분: timely(제때 크롤 — 분석되면 랭킹 노출) vs 윈도우 전용(늦크롤 — 분석돼도 인플루언서
	 * 상세만). 항등식: candidates = timelyTotal + windowTotal, 각 트랙 total = 기분석 + 미분석.
	 */
	public record Heavy(
			long candidates,
			long timelyTotal, long timelyDone,
			long windowTotal, long windowDone,
			long servingContents, long servingAnalyzed,
			long immaturePool, long lateExcluded,
			long beautyHandles, long beautyCopied,
			Instant computedAt) {

		public long timelyPending() {
			return timelyTotal - timelyDone;
		}

		public long windowPending() {
			return windowTotal - windowDone;
		}

		/** 진짜 잔여 — 후보 ∩ 미분석 (사용자가 알고 싶던 그 숫자). */
		public long truePending() {
			return timelyPending() + windowPending();
		}
	}

	/** 계정 축 (raw influencer, 현재 스냅샷 — 매 요청 싼 카운트). nonBeauty는 미판정 포함. */
	public record Accounts(long total, long qualified, long beautyIndividual,
			long beautyCompany, long nonBeauty, long judgedToday) {
	}

	/**
	 * 대시보드 집계 묶음. heavy가 null이면 무거운 집계 전("집계 중…") 또는 실패(candidatesError로 구분).
	 * analyzed/timelyMarked/backfillMarked는 content_analyses 역대 누적(마킹 기준) — 모수 리비전이
	 * 섞여 있어 v3에선 각주 취급. copiedAccounts도 누적 핸들(탈락 계정 포함) — 현 모수 대비는 heavy.
	 */
	public record Funnel(long rawContents,
			long analyzed, long timelyMarked, long backfillMarked,
			long served, long mirrorAccounts,
			long copiedAccounts, long accountTarget,
			Accounts accounts, Heavy heavy,
			int todayPlanned, int daysToFull,
			int pinDays, int slackDays,
			String candidatesError) {
	}

	/**
	 * 파이프라인 건강·처리량 — 전부 싼 쿼리(인메모리 RunHistory와 달리 재시작에도 영속).
	 * last*At은 각 산출물의 최신 시각(신선도), today*는 오늘(KST) 처리량.
	 * lastMirrorAt은 미러된 지표 스냅샷 최신(captured_at) — 크롤 지표 신선도. 미러 잡 시각이 아니라
	 * "크롤이 지표를 마지막으로 잡은 때"다(07-20 크롤 정지를 이 신호가 잡았다 — 라벨도 그렇게 붙인다).
	 */
	public record Health(long todayAnalyzed, long todayAccountCopied,
			Instant lastAnalysisAt, Instant lastMirrorAt, Instant lastAccountCopyAt) {
	}

	/**
	 * 자격 밖 분해 — 캡션 있는 서빙 콘텐츠(후보 뷰의 모수) 중 후보가 못 된 것의 이유를 나눈다.
	 * mature_pool은 04 뷰의 성숙 가드(제때창 완전 경과 — KST 캘린더일)와 동일식: 후보 ⊆ mature_pool ⊆
	 * caption_pool이 성립해야 미성숙(caption−mature)·영구 제외(mature−후보)가 둘 다 음수 없이 나온다.
	 */
	private static final String POOL_SQL = """
			SELECT
			  count(*) AS caption_pool,
			  count(*) FILTER (
			    WHERE (v.posted_at AT TIME ZONE 'Asia/Seoul')::date
			            + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
			            + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 1)
			          <= (now() AT TIME ZONE 'Asia/Seoul')::date
			  ) AS mature_pool
			FROM v_contents v
			WHERE v.caption IS NOT NULL AND btrim(v.caption) <> ''
			""";

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final AnalyticsSettings settings;

	private volatile Heavy cachedHeavy;
	private volatile Instant heavyComputedAt;
	/** 마지막 무거운 집계 시도의 실패 사유 — 성공하면 null. "집계 중"과 "집계 실패"를 UI에서 가르는 신호.
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
		Accounts accounts = accounts();
		// content_analyses 집계 1패스 — 전체·timely·late_backfill을 한 번에 (개별 count 반복 제거).
		Map<String, Object> ca = analysis.queryForMap("""
				SELECT count(*) AS total,
				       count(*) FILTER (WHERE metric_timeliness = 'timely')        AS timely,
				       count(*) FILTER (WHERE metric_timeliness = 'late_backfill') AS backfill
				FROM content_analyses""");
		long served = count(analysis, "SELECT count(*) FROM contents");
		long mirrorAccounts = count(analysis, "SELECT count(*) FROM accounts");
		long copied = count(analysis, "SELECT count(DISTINCT handle) FROM account_analyses");
		Heavy heavy = cachedHeavy;
		// 잔여는 크로스 DB 대조의 "진짜 잔여"(후보 ∩ 미분석) — 누적 마킹 수로 빼면 리비전이 섞인다.
		long remaining = heavy == null ? -1 : heavy.truePending();
		return new Funnel(rawContents,
				num(ca.get("total")), num(ca.get("timely")), num(ca.get("backfill")),
				served, mirrorAccounts, copied, accountTarget(), accounts, heavy,
				remaining < 0 ? 0 : todayPlanned(remaining),
				remaining < 0 ? 0 : daysToFull(remaining),
				settings.metricPinDays(), settings.analyzeTimelySlackDays(),
				heavyError);
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
		Map<String, Object> c = analysis.queryForMap("""
				SELECT max(analyzed_at) AS last_at,
				       count(*) FILTER (WHERE analyzed_at AT TIME ZONE 'Asia/Seoul'
				                        >= (now() AT TIME ZONE 'Asia/Seoul')::date) AS today
				FROM content_analyses""");
		Map<String, Object> a = analysis.queryForMap("""
				SELECT max(analyzed_at) AS last_at,
				       count(*) FILTER (WHERE analyzed_at AT TIME ZONE 'Asia/Seoul'
				                        >= (now() AT TIME ZONE 'Asia/Seoul')::date) AS today
				FROM account_analyses""");
		Instant lastMirror = instant(analysis,
				"SELECT max(captured_at) FROM content_metric_snapshots");
		return new Health(num(c.get("today")), num(a.get("today")),
				toInstant(c.get("last_at")), lastMirror, toInstant(a.get("last_at")));
	}

	/** 계정 축 카운트 1패스 — influencer 테이블(1.4만 행)이라 매 요청 감당. 뷰티 재판정 중엔 분 단위 변동. */
	private Accounts accounts() {
		Map<String, Object> r = raw.queryForMap("""
				SELECT count(*) AS total,
				       count(*) FILTER (WHERE status = 'QUALIFIED') AS qualified,
				       count(*) FILTER (WHERE status = 'QUALIFIED' AND beauty AND NOT beauty_company) AS beauty_individual,
				       count(*) FILTER (WHERE status = 'QUALIFIED' AND beauty AND beauty_company) AS beauty_company,
				       count(*) FILTER (WHERE beauty_judged_at AT TIME ZONE 'Asia/Seoul'
				                        >= (now() AT TIME ZONE 'Asia/Seoul')::date) AS judged_today
				FROM influencer""");
		long qualified = num(r.get("qualified"));
		long beautyIndividual = num(r.get("beauty_individual"));
		long beautyCompany = num(r.get("beauty_company"));
		return new Accounts(num(r.get("total")), qualified, beautyIndividual, beautyCompany,
				qualified - beautyIndividual - beautyCompany, num(r.get("judged_today")));
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

	/**
	 * 오늘 처리 예정 — LIMIT 폐지(2026-07-23, ContentAnalysisJob timely/late_backfill 분리)
	 * 이후 잡은 매 실행마다 자격 후보 전량을 시도한다(실질 상한은 LLM 쿼타 429). "오늘 예정"은
	 * 그래서 잔여 전체다.
	 */
	static int todayPlanned(long remaining) {
		return (int) Math.min(Math.max(0, remaining), Integer.MAX_VALUE);
	}

	/**
	 * 완주까지 남은 실행 횟수 — 잡이 매 실행 전량을 시도하므로 잔여가 있으면 다음 1회뿐이다.
	 * 쿼타 소진으로 이월되는 경우는 이 수치가 아니라 RunHistory의 QUOTA_CARRYOVER 이력으로 별도 노출된다.
	 */
	static int daysToFull(long remaining) {
		return remaining > 0 ? 1 : 0;
	}

	/** 트랙별 대조 (G1 핵심) — 후보(short_code→timely)와 기분석 셋의 Java 교집합. 항등식 보장 지점. */
	record TrackSplit(long timelyTotal, long timelyDone, long windowTotal, long windowDone) {
	}

	static TrackSplit split(Map<String, Boolean> candidates, Set<String> analyzed) {
		long timelyTotal = 0;
		long timelyDone = 0;
		long windowTotal = 0;
		long windowDone = 0;
		for (Map.Entry<String, Boolean> e : candidates.entrySet()) {
			boolean done = analyzed.contains(e.getKey());
			if (Boolean.TRUE.equals(e.getValue())) {
				timelyTotal++;
				if (done) timelyDone++;
			} else {
				windowTotal++;
				if (done) windowDone++;
			}
		}
		return new TrackSplit(timelyTotal, timelyDone, windowTotal, windowDone);
	}

	private void refreshHeavyIfStale() {
		boolean stale = heavyComputedAt == null
				|| Instant.now().isAfter(heavyComputedAt.plus(TTL));
		if (stale && computing.compareAndSet(false, true)) {
			Thread.ofVirtual().name("pipeline-stats").start(() -> {
				try {
					cachedHeavy = computeHeavy();
					heavyComputedAt = Instant.now();
					heavyError = null;
				} catch (RuntimeException e) {
					// 사유를 남긴다 — 캐시가 없으면 UI가 "집계 중"이 아니라 "집계 실패"로 표시해야 한다.
					heavyError = rootMessage(e);
					log.warn("무거운 집계 실패 — 이전 캐시 유지", e);
				} finally {
					computing.set(false);
				}
			});
		}
	}

	/**
	 * 무거운 집계 본체 — analysis 셋 로드(기분석 1.7만·카피 1.5천, ContentAnalysisJob의 기존 패턴) 후
	 * raw 쪽을 REPEATABLE READ 읽기 전용 트랜잭션 하나로 스캔한다. 후보·풀·서빙·계정이 같은 스냅샷을
	 * 봐야 미성숙·영구 제외 같은 차집합이 음수 없이 성립한다(구현 전엔 "반드시 한 statement" 주석으로
	 * 지키던 제약 — 여러 결과셋이 필요해져 트랜잭션 스냅샷으로 승격).
	 */
	private Heavy computeHeavy() {
		Set<String> analyzedCodes = new HashSet<>(
				analysis.queryForList("SELECT short_code FROM content_analyses", String.class));
		Set<String> copiedHandles = new HashSet<>(
				analysis.queryForList("SELECT DISTINCT handle FROM account_analyses", String.class));
		return raw.execute((Connection con) -> {
			boolean oldAutoCommit = con.getAutoCommit();
			int oldIsolation = con.getTransactionIsolation();
			try {
				con.setAutoCommit(false);
				con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
				con.setReadOnly(true);
				try (Statement st = con.createStatement()) {
					// ① 분석 자격 — 트랙(timely/윈도우) × (기분석/미분석) 4분할을 Java에서 대조.
					Map<String, Boolean> candidateRows = new java.util.LinkedHashMap<>();
					try (ResultSet rs = st.executeQuery(
							"SELECT short_code, timely FROM v_analysis_candidates")) {
						while (rs.next()) {
							candidateRows.put(rs.getString(1), rs.getBoolean(2));
						}
					}
					TrackSplit tracks = split(candidateRows, analyzedCodes);
					// ② 자격 밖 분해 — 캡션 풀·성숙 풀(04 뷰 성숙 가드와 동일식).
					long captionPool;
					long maturePool;
					try (ResultSet rs = st.executeQuery(POOL_SQL)) {
						rs.next();
						captionPool = rs.getLong("caption_pool");
						maturePool = rs.getLong("mature_pool");
					}
					// ③ 서빙 커버리지 (G2) — 분모·분자 모두 "현재 서빙 모수" 기준.
					long serving = 0;
					long servingAnalyzed = 0;
					try (ResultSet rs = st.executeQuery(
							"SELECT short_code FROM v_serving_content")) {
						while (rs.next()) {
							serving++;
							if (analyzedCodes.contains(rs.getString(1))) servingAnalyzed++;
						}
					}
					// ④ 계정 카피 (G1) — 현 모수 핸들 ∩ 카피 보유. 누적 카피(탈락 계정 포함)와 분리.
					long beautyHandles = 0;
					long beautyCopied = 0;
					try (ResultSet rs = st.executeQuery("SELECT handle FROM v_accounts")) {
						while (rs.next()) {
							beautyHandles++;
							if (copiedHandles.contains(rs.getString(1))) beautyCopied++;
						}
					}
					con.commit();
					long candidates = tracks.timelyTotal() + tracks.windowTotal();
					return new Heavy(candidates,
							tracks.timelyTotal(), tracks.timelyDone(),
							tracks.windowTotal(), tracks.windowDone(),
							serving, servingAnalyzed,
							Math.max(0, captionPool - maturePool),
							Math.max(0, maturePool - candidates),
							beautyHandles, beautyCopied, Instant.now());
				}
			} catch (java.sql.SQLException e) {
				con.rollback();
				throw e;
			} finally {
				con.setReadOnly(false);
				con.setTransactionIsolation(oldIsolation);
				con.setAutoCommit(oldAutoCommit);
			}
		});
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
