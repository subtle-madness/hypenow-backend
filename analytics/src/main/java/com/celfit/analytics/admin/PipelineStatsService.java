package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.archive.ImageArchiveJob;
import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.CopyRules;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
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
	 * 무거운 집계 스냅샷 (G1·G2) - raw 쪽 수치는 REPEATABLE READ 한 트랜잭션에서 읽어 서로 정합.
	 * analysis 쪽 셋(기분석 short_code·카피 handle)은 다른 DB라 스냅샷 정합이 원리적으로 불가 -
	 * 집계 시각 표기로 보완한다(후보 뷰는 판정 진행 중엔 분 단위로 변한다).
	 *
	 * <p>트랙 구분: timely(제때 크롤 - 분석되면 랭킹 노출) vs 윈도우 전용(늦크롤 - 분석돼도 인플루언서
	 * 상세만). 항등식: candidates = timelyTotal + windowTotal,
	 * 각 트랙 total = 파트 B 완료(Done) + 미완(Pending).
	 *
	 * <p>2026-09-03 2단계 분리: 행 존재 = 기분석이라는 등식이 깨졌다. 파트 A만 채워진 행
	 * (metric_timeliness='pending')은 랭킹에 못 뜨므로 Done이 아니라 FactsOnly로 따로 세고,
	 * Pending(해야 할 일)에는 그대로 포함한다. factCandidates/factAnalyzed는 파트 A 잡의 대상·완료다.
	 */
	public record Heavy(
			long candidates,
			long timelyTotal, long timelyDone, long timelyFactsOnly,
			long windowTotal, long windowDone, long windowFactsOnly,
			long factCandidates, long factAnalyzed,
			long servingContents, long servingAnalyzed,
			long immaturePool, long lateExcluded,
			long beautyHandles, long beautyCopied,
			ArchiveCoverage archive,
			Instant computedAt) {

		public long timelyPending() {
			return timelyTotal - timelyDone;
		}

		public long windowPending() {
			return windowTotal - windowDone;
		}

		/** 진짜 잔여 - 후보 ∩ 파트 B 미완('사실만' 포함). */
		public long truePending() {
			return timelyPending() + windowPending();
		}

		/** 사실만 채워진 후보 - 화면에 광고 판정·카테고리는 이미 떠 있고 해석만 대기 중이다. */
		public long factsOnlyTotal() {
			return timelyFactsOnly + windowFactsOnly;
		}

		/** 파트 A 잡의 잔여 - 캡션은 있는데 아직 사실 추출이 안 된 것. */
		public long factPending() {
			return factCandidates - factAnalyzed;
		}
	}

	/**
	 * 아카이브 커버리지 — ImageArchiveJob의 대상 선정 규칙과 동일한 대조(썸네일=미기록 short_code,
	 * 프로필=원본 파일명 불일치). 잡과 규칙이 어긋나면 카드 잔여와 실제 처리량이 어긋난다.
	 */
	public record ArchiveCoverage(long thumbTargets, long thumbArchived,
			long profileTargets, long profileFresh) {

		public long thumbPending() {
			return thumbTargets - thumbArchived;
		}

		public long profilePending() {
			return profileTargets - profileFresh;
		}

		public long targets() {
			return thumbTargets + profileTargets;
		}

		public long archived() {
			return thumbArchived + profileFresh;
		}

		public long pending() {
			return thumbPending() + profilePending();
		}
	}

	/** 대상 밖 기록(archived에만 있는 키)은 안 센다 — 잡이 다시 볼 일 없는 키라 커버리지 분자 부풀림 방지. */
	static ArchiveCoverage archiveCoverage(Set<String> thumbCodes, Set<String> archivedThumbs,
			Map<String, String> profileUrls, Map<String, String> archivedProfileSources) {
		long thumbArchived = thumbCodes.stream().filter(archivedThumbs::contains).count();
		long profileFresh = 0;
		for (Map.Entry<String, String> e : profileUrls.entrySet()) {
			String archivedSource = archivedProfileSources.get(e.getKey());
			if (archivedSource == null) {
				continue;
			}
			try {
				if (ImageArchiveJob.sourceName(e.getValue()).equals(archivedSource)) {
					profileFresh++;
				}
			} catch (RuntimeException ex) {
				// URL 파싱 불가 — 잡과 동일하게 '변경 취급'(갱신 대기로 남긴다)
			}
		}
		return new ArchiveCoverage(thumbCodes.size(), thumbArchived,
				profileUrls.size(), profileFresh);
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
			long analyzed, long timelyMarked, long backfillMarked, long pendingMarked,
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
	 * lastMirrorAt은 2026-07-30부터 소스가 content_metric_snapshots 미러(제거됨)가 아니라
	 * contents.metric_captured_at(핀 지표 캡처 시각)이다 — 이름은 유지하되 실체가 바뀌었다.
	 * 대체가 안전한 근거(실측): MAX(contents.metric_captured_at) = MAX(content_metric_snapshots.captured_at)
	 * (마이크로초까지 일치). 이유는 v_pinned_metrics의 핀 우선순위 — 성숙한(3일 지난) 콘텐츠만
	 * "+3일 시점 스냅샷"으로 고정되고, 미성숙 콘텐츠는 우선순위 ②(완비된 최신 스냅샷)로 폴백해
	 * 최신 크롤 시각을 그대로 반영한다. 핀 값은 스냅샷 captured_at의 부분집합이라
	 * MAX(pinned) ≤ MAX(snapshot)이 항상 성립 — 이 신호가 실제보다 더 신선하게 보고할 수 없다(과대
	 * 신선 보고 불가, 크롤 정지를 놓칠 수 없다). 여전히 "크롤이 지표를 마지막으로 잡은 때"다
	 * (07-20 크롤 정지를 이 신호가 잡았다 — 라벨도 그렇게 붙인다).
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
		// content_analyses 집계 1패스 - 전체·timely·late_backfill·pending을 한 번에 (개별 count 반복 제거).
		Map<String, Object> ca = analysis.queryForMap("""
				SELECT count(*) AS total,
				       count(*) FILTER (WHERE metric_timeliness = 'timely')        AS timely,
				       count(*) FILTER (WHERE metric_timeliness = 'late_backfill') AS backfill,
				       count(*) FILTER (WHERE metric_timeliness = 'pending')       AS pending
				FROM content_analyses""");
		long served = count(analysis, "SELECT count(*) FROM contents");
		long mirrorAccounts = count(analysis, "SELECT count(*) FROM accounts");
		long copied = count(analysis, "SELECT count(DISTINCT handle) FROM account_analyses");
		Heavy heavy = cachedHeavy;
		// 잔여는 크로스 DB 대조의 "진짜 잔여"(후보 ∩ 미분석) — 누적 마킹 수로 빼면 리비전이 섞인다.
		long remaining = heavy == null ? -1 : heavy.truePending();
		return new Funnel(rawContents,
				num(ca.get("total")), num(ca.get("timely")), num(ca.get("backfill")), num(ca.get("pending")),
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
		// content_metric_snapshots 미러 제거(2026-07-30)로 소스 교체 — 위 Health 클래스 주석 참조.
		Instant lastMirror = instant(analysis,
				"SELECT max(metric_captured_at) FROM contents");
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

	/**
	 * 계정 카피 대상 수 — AccountAnalysisJob.ELIGIBLE_WHERE(잡의 대상 정의와 단일 정본)를 count로.
	 * 07-27 개편(perf_summary 3컬럼) 백필 대상(구 스키마 행)까지 잡과 동형으로 잡아야 대시보드
	 * "대상" 수치가 실제 재분석 후보와 어긋나지 않는다.
	 * 패키지 가시성 — PipelineStatsServiceTest가 raw DB 픽스처 없이 이 쿼리만 대고 검증한다.
	 */
	long accountTarget() {
		Long v = analysis.queryForObject("""
				SELECT count(*)
				FROM account_summaries s
				""" + AccountAnalysisJob.ELIGIBLE_WHERE,
				Long.class, settings.accountAnalyzeCooldownDays(), CopyRules.VERSION);
		return v == null ? 0 : v;
	}

	/**
	 * "카피 완료" 핸들 집합 — 계정별 최신 행이 신 스키마(perf_summary 3컬럼, V40)를 보유할 때만.
	 * 행 존재만으로 세면 07-27 개편 백필 대상(구 스키마 최신 행)까지 완료로 잘못 잡힌다. 개편 직후엔
	 * G1 카피율이 0%대에서 시작해 백필 진행대로 차오르는 게 의도된 표시다.
	 * 패키지 가시성 — PipelineStatsServiceTest가 raw DB 픽스처 없이 이 쿼리만 대고 검증한다.
	 */
	Set<String> copiedHandles() {
		return new HashSet<>(analysis.queryForList("""
				SELECT handle FROM (
				  SELECT DISTINCT ON (handle) handle, perf_summary
				  FROM account_analyses ORDER BY handle, analyzed_at DESC
				) latest WHERE perf_summary IS NOT NULL""", String.class));
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

	/** 트랙별 대조 (G1 핵심) - 후보(short_code→timely) × (파트 B 완료 / 사실만 / 미착수). */
	record TrackSplit(long timelyTotal, long timelyDone, long timelyFactsOnly,
			long windowTotal, long windowDone, long windowFactsOnly) {
	}

	/**
	 * @param analyzed content_analyses에 행이 있는 short_code 전량
	 * @param factsOnly 그중 파트 A만 채워진 것(metric_timeliness='pending').
	 *        행 존재만으로 Done을 세면 파트 A 행이 "랭킹 노출 가능"으로 잘못 잡힌다(2026-09-03).
	 */
	static TrackSplit split(Map<String, Boolean> candidates, Set<String> analyzed, Set<String> factsOnly) {
		long timelyTotal = 0;
		long timelyDone = 0;
		long timelyFacts = 0;
		long windowTotal = 0;
		long windowDone = 0;
		long windowFacts = 0;
		for (Map.Entry<String, Boolean> e : candidates.entrySet()) {
			boolean facts = factsOnly.contains(e.getKey());
			boolean done = analyzed.contains(e.getKey()) && !facts;
			if (Boolean.TRUE.equals(e.getValue())) {
				timelyTotal++;
				if (done) timelyDone++;
				if (facts) timelyFacts++;
			} else {
				windowTotal++;
				if (done) windowDone++;
				if (facts) windowFacts++;
			}
		}
		return new TrackSplit(timelyTotal, timelyDone, timelyFacts, windowTotal, windowDone, windowFacts);
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
		// 파트 A만 채워진 행(2026-09-03) - 부분 인덱스로 좁혀진 집합이라 통짜 로드가 싸다.
		Set<String> factsOnlyCodes = new HashSet<>(analysis.queryForList(
				"SELECT short_code FROM content_analyses WHERE metric_timeliness = 'pending'", String.class));
		Set<String> copiedHandles = copiedHandles();
		// 아카이브 기록 셋 — 다른 DB(analysis)라 raw 스냅샷과 정합 불가는 기존 셋들과 같은 한계.
		Set<String> archivedThumbs = new HashSet<>(analysis.queryForList(
				"SELECT key FROM image_assets WHERE kind = 'thumbnail'", String.class));
		Map<String, String> archivedProfiles = new HashMap<>();
		analysis.query("SELECT key, source_name FROM image_assets WHERE kind = 'profile'",
				rs -> {
					archivedProfiles.put(rs.getString(1), rs.getString(2));
				});
		return raw.execute((Connection con) -> {
			boolean oldAutoCommit = con.getAutoCommit();
			int oldIsolation = con.getTransactionIsolation();
			try {
				con.setAutoCommit(false);
				con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
				con.setReadOnly(true);
				try (Statement st = con.createStatement()) {
					// ① 분석 자격 - 파트 A 입구 뷰를 1회 스캔해 파트 A 축과 파트 B 트랙을 함께 얻는다.
					//    v_analysis_candidates = v_fact_candidates WHERE mature 이므로 mature 행만
					//    추리면 구 스캔과 결과가 같다(스캔 횟수 불변 - 뷰 평가가 이 집계의 본체다).
					Map<String, Boolean> candidateRows = new java.util.LinkedHashMap<>();
					long factCandidates = 0;
					long factAnalyzed = 0;
					try (ResultSet rs = st.executeQuery(
							"SELECT short_code, timely, mature FROM v_fact_candidates")) {
						while (rs.next()) {
							String shortCode = rs.getString(1);
							factCandidates++;
							if (analyzedCodes.contains(shortCode)) {
								factAnalyzed++;
							}
							if (rs.getBoolean(3)) {
								candidateRows.put(shortCode, rs.getBoolean(2));
							}
						}
					}
					TrackSplit tracks = split(candidateRows, analyzedCodes, factsOnlyCodes);
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
					// ⑤ 아카이브 커버리지 — 잡(ImageArchiveJob)의 대상 쿼리와 동일한 선정으로 대조.
					Set<String> thumbCodes = new HashSet<>();
					try (ResultSet rs = st.executeQuery(
							"SELECT short_code FROM v_contents WHERE thumbnail_url IS NOT NULL")) {
						while (rs.next()) {
							thumbCodes.add(rs.getString(1));
						}
					}
					Map<String, String> profileUrls = new HashMap<>();
					try (ResultSet rs = st.executeQuery(
							"SELECT handle, profile_image_url FROM v_accounts WHERE profile_image_url IS NOT NULL")) {
						while (rs.next()) {
							profileUrls.put(rs.getString(1), rs.getString(2));
						}
					}
					con.commit();
					long candidates = tracks.timelyTotal() + tracks.windowTotal();
					return new Heavy(candidates,
							tracks.timelyTotal(), tracks.timelyDone(), tracks.timelyFactsOnly(),
							tracks.windowTotal(), tracks.windowDone(), tracks.windowFactsOnly(),
							factCandidates, factAnalyzed,
							serving, servingAnalyzed,
							Math.max(0, captionPool - maturePool),
							Math.max(0, maturePool - candidates),
							beautyHandles, beautyCopied,
							archiveCoverage(thumbCodes, archivedThumbs, profileUrls, archivedProfiles),
							Instant.now());
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
