package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.BeautyTaxonomy;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.ContentFactsPort;
import com.celfit.analytics.llm.ContentInsightPort;
import com.celfit.analytics.llm.ContentSynthesisPort;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.ContentToSynthesize;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.llm.GeminiContentAnalyzer;
import com.celfit.analytics.llm.GeminiContentSynthesizer;
import com.celfit.analytics.llm.Synthesis;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 콘텐츠 분석 배치 (스펙 §6, 2단계 분리는 2026-09-03 설계 §4-4·§5). {@code analytics.analyze-mode}
 * 런타임 토글(app_setting)로 두 동작이 갈린다:
 * <ul>
 * <li>UNIFIED(기본, mode=unified) - 현행 통합 1콜. raw 후보 뷰(v_analysis_candidates)의 후보 중
 *     미분석 AND (댓글 없음 OR 분류 완료)에 사실+해석을 한 번에 INSERT한다(분석 시점 고정·불변,
 *     재분석 없음).
 * <li>FACTS(mode=split, runFacts()) - 파트 A(사실). v_fact_candidates(성숙·timely 무관 - D+1
 *     새벽부터 가능)의 '행 존재'만 제외 게이트로 삼아 insertFacts로 INSERT한다
 *     (metric_timeliness='pending').
 * <li>SYNTHESIS(mode=split, run()·runLateBackfill()) - 파트 B(해석). v_analysis_candidates ∩
 *     pending(파트 A는 끝났고 파트 B는 아직인 행) 후보에 updateSynthesis로 UPDATE해 시점을
 *     timely/late_backfill로 확정한다.
 * </ul>
 * mode=unified면 runFacts()는 no-op - 토글을 켜기 전까지 운영 행동은 하나도 바뀌지 않는다.
 * 자격(캘린더일 timely·성숙·윈도우)은 뷰 소관(07-28 정합), 제외는 여기 Java diff 소관.
 * timely(run())와 late_backfill(runLateBackfill())은 서로 다른 진입점 - 예산·스케줄이 별도라
 * 백필 후보가 몰려도 매일 갱신돼야 할 timely 분석이 밀리지 않는다(2026-07-23 설계, 뷰의
 * timely 컬럼으로 서로소 분할).
 * 속성 분석은 캡션 주·썸네일 보조 (2026-07-14 캡션 분류 스펙) - 썸네일 만료여도 캡션으로 5종 산출.
 * 콘텐츠 단위 실패 격리: 한 건 실패는 로그 후 계속 (B2 리뷰 반영).
 */
public class ContentAnalysisJob {

	private static final Logger log = LoggerFactory.getLogger(ContentAnalysisJob.class);
	/** 계정 집계마저 없는 이례적 케이스용 — 전부 null (프롬프트가 앵커 없이 절제 처리). */
	private static final Baseline EMPTY_BASELINE =
			new Baseline(null, null, null, null, null, null, null, null, null, null);

	// 후보 자격은 raw 후보 뷰가 정본(07-28 캘린더일 정합 — 뷰 04 주석 참조): 캘린더일(KST)
	// timely 판정·성숙(창닫힘)·최근 N개 윈도우 게이트 전부 뷰 소관. 잡은 timely 플래그로 두
	// 진입점을 서로소 분할(WHERE timely = ?)하고 마킹에 그대로 쓴다. 구 간격식(캡처가 업로드
	// +pin~+pin+slack일 '시간 간격' 안) 판정은 뷰의 캘린더일 정정(07-20)과 갈라져 일 수백 건이
	// late_backfill로 새던 원인이라 제거 — 수식은 뷰 한 곳에만 둔다.
	// '이미 분석됨'·댓글 게이트 제외는 analysis DB 상태라 SQL 조인이 불가 — Java 셋 대조(diff)로
	// 뺀다(뷰 주석의 원 설계: "'이미 분석됨' 제외·정렬 정책은 Java 몫").
	// 재료(캡션·지표·핸들)도 이 뷰에서 함께 읽는다(2026-08-31) — 구 버전은 analysis DB의 미러
	// 테이블 contents에서 다시 읽었는데, 미러는 뷰티 서빙 모수라 F&B 후보가 전부 "미러 부재"로
	// 스킵됐다(04 모수를 넓혀도 로그만 남기고 사라지는 구조). 뷰가 이미 같은 컬럼을 들고 있어
	// 콘텐츠당 조회 1회가 줄고, 미러 지연으로 뷰티 후보가 하루 밀리던 스킵도 함께 사라진다.
	// 컬럼 이름은 구 contents 조회와 1:1로 맞춘다 — GeminiBatchLines가 이 키 계약에 의존한다.
	// CANDIDATES_SQL·FACT_CANDIDATES_SQL이 같은 SELECT 목록을 쓰므로 상수로 한 곳에 모은다(M6).
	private static final String CANDIDATE_COLUMNS =
			"short_code, account_handle, caption, content_type, thumbnail_url, views, likes, comments, ad_marked";

	private static final String CANDIDATES_SQL = """
			SELECT %s
			FROM v_analysis_candidates
			WHERE timely = ?
			ORDER BY metric_captured_at DESC NULLS LAST, short_code""".formatted(CANDIDATE_COLUMNS);

	// 파트 A(사실) 입구 - 성숙·timely 무관. 뷰가 `NOT mature OR timely OR in_window`로 이미 잘라
	// 준다(성숙 ∧ 늦크롤 ∧ 윈도우 밖 = 영구 제외는 파트 A에도 열지 않는다 - 04 뷰 주석 참조).
	private static final String FACT_CANDIDATES_SQL = """
			SELECT %s
			FROM v_fact_candidates
			ORDER BY metric_captured_at DESC NULLS LAST, short_code""".formatted(CANDIDATE_COLUMNS);

	/** 분석 단계 축(2026-09-03). UNIFIED=현행 통합 1콜, FACTS=파트 A(사실), SYNTHESIS=파트 B(해석). */
	public enum Phase { UNIFIED, FACTS, SYNTHESIS }

	/** 파트 A는 기준선을 안 쓴다 - 뷰 스캔(운영 실측 분 단위)을 통째로 건너뛰기 위한 상수. */
	private static final Baselines EMPTY_BASELINES = new Baselines(Map.of(), Map.of());

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final ContentInsightPort insight; // ②속성+③종합 통합 1콜 (07-18 확정)
	private final AnalyticsSettings settings;
	private final boolean thumbnailEnabled; // 썸네일 첨부 게이트 — off여도 캡션 기반 속성은 산출
	private final Predicate<String> thumbnailAlive;
	private final ProgressReporter reporter;
	private final ProgressReporter backfillReporter; // runLateBackfill() 진행률 — run()의 reporter와 별도 JobName
	private final ObjectMapper json = new ObjectMapper();
	// 배치 전송(2026-08-11, Vertex 배치 50% 할인) — batchApi가 null이면 배치 미지원 프로바이더라
	// transport=batch여도 온라인으로 폴백한다(LlmConfig.geminiApi()의 무료 gemini 폴백과 동형 안전망).
	private final GeminiBatchApi batchApi;
	private final BeautyTaxonomyLoader taxonomyLoader;
	private final ContentBatchCollectJob collectJob;
	private final ProgressReporter factsReporter; // runFacts() 진행률 - JobName.FACT_ANALYZE
	private final ContentFactsPort factsPort;       // null이면 split 미지원 프로바이더(anthropic)
	private final ContentSynthesisPort synthesisPort; // null이면 split 미지원 프로바이더

	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive,
			ProgressReporter reporter, ProgressReporter backfillReporter) {
		this(rawJdbcTemplate, analysisDataSource, insight, settings, thumbnailEnabled, thumbnailAlive,
				reporter, backfillReporter, null, null);
	}

	/**
	 * @param batchApi 배치 전송 제출·상태 확인용 — null이면 배치 미지원 프로바이더(온라인 폴백).
	 * @param taxonomyLoader 배치 요청의 시스템 프롬프트 조립용(뷰티 분류표).
	 */
	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive,
			ProgressReporter reporter, ProgressReporter backfillReporter,
			GeminiBatchApi batchApi, BeautyTaxonomyLoader taxonomyLoader) {
		this(rawJdbcTemplate, analysisDataSource, insight, settings, thumbnailEnabled, thumbnailAlive,
				reporter, backfillReporter, batchApi, taxonomyLoader,
				ProgressReporter.NOOP, null, null);
	}

	/**
	 * 2단계 분리(analytics.analyze-mode=split) 지원 생성자.
	 *
	 * @param factsPort 파트 A 온라인 폴백 - null이면 split 미지원 프로바이더(anthropic 롤백 경로).
	 * @param synthesisPort 파트 B 온라인 폴백 - 같은 규칙.
	 */
	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive,
			ProgressReporter reporter, ProgressReporter backfillReporter,
			GeminiBatchApi batchApi, BeautyTaxonomyLoader taxonomyLoader,
			ProgressReporter factsReporter, ContentFactsPort factsPort,
			ContentSynthesisPort synthesisPort) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.insight = insight;
		this.settings = settings;
		this.thumbnailEnabled = thumbnailEnabled;
		this.thumbnailAlive = thumbnailAlive;
		this.reporter = reporter;
		this.backfillReporter = backfillReporter;
		this.batchApi = batchApi;
		this.taxonomyLoader = taxonomyLoader;
		this.factsReporter = factsReporter;
		this.factsPort = factsPort;
		this.synthesisPort = synthesisPort;
		this.collectJob = new ContentBatchCollectJob(analysisDataSource, batchApi, taxonomyLoader, settings);
	}

	/** raw v_analysis_account_baseline·v_analysis_baseline 1회 로딩 결과 — run()·runLateBackfill() 공유. */
	private record Baselines(Map<String, Baseline> accountBaseline, Map<String, Baseline> withBaseline) {}

	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>mode=unified면 현행 통합 1콜, split이면 파트 B(해석)만 만든다. 어느 쪽이든 후보는
	 * 성숙한 timely 분이라 랭킹 진입 시점은 변하지 않는다.
	 */
	public JobResult run() {
		return runQuery(settings.splitAnalyzeMode() ? Phase.SYNTHESIS : Phase.UNIFIED, true, reporter);
	}

	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>후보 뷰의 NOT timely 후보(= 최근 N개 윈도우 안 늦크롤) 전량. run()과 상호 배타 -
	 * 같은 뷰의 timely 컬럼으로 서로소 분할이라 같은 short_code가 두 진입점에 동시에 잡히지 않는다.
	 */
	public JobResult runLateBackfill() {
		return runQuery(settings.splitAnalyzeMode() ? Phase.SYNTHESIS : Phase.UNIFIED, false,
				backfillReporter);
	}

	/**
	 * 파트 A(사실) 전용 진입점 - JobName.FACT_ANALYZE. 성숙·timely와 무관하게 캡션만 보고 돌므로
	 * D+1 새벽에 실행할 수 있다(2026-09-03 2단계 분리 설계 §2-2).
	 *
	 * <p>mode=unified면 통합 콜이 사실까지 만들므로 no-op 로그만 남기고 끝난다 - 배포 후에도
	 * 토글을 켜기 전까지 운영 행동은 하나도 바뀌지 않는다.
	 */
	public JobResult runFacts() {
		if (!settings.splitAnalyzeMode()) {
			log.info("analytics.analyze-mode=unified - 파트 A 잡 no-op(통합 콜이 사실까지 만든다)");
			return new JobResult(0, 0, false);
		}
		return runQuery(Phase.FACTS, false, factsReporter);
	}

	private JobResult runQuery(Phase phase, boolean timely, ProgressReporter progress) {
		if (phase != Phase.UNIFIED && (factsPort == null || synthesisPort == null)) {
			// batchApiOrNull과 같은 관용구로 JobConfig가 anthropic이면 null을 넣는다.
			// 잡을 조용히 no-op으로 두면 "왜 안 도는지"를 로그로 알 수 없어 명시적으로 죽인다.
			throw new IllegalStateException(
					"analytics.analyze-mode=split은 gemini/vertex 프로바이더에서만 지원한다 - "
					+ "롤백하려면 app_setting analytics.analyze-mode를 unified로");
		}
		// submitBatch가 실제로 채택할 경로와 동일한 조건 - SYNTHESIS는 이미지를 안 보내 vlm-enabled과
		// 무관하게 배치를 탄다(아래 if/else와 동형, 분기별 경고 문구가 달라 하나로 합치지 않는다).
		boolean useBatch = settings.batchTransportEnabled() && batchApi != null
				&& !(thumbnailEnabled && phase != Phase.SYNTHESIS);
		if (useBatch) {
			// 배치 제출 전 pending 잔여를 먼저 수거한다(전날 미수거분 회수) - resolveTargets보다
			// 반드시 먼저 실행해야 한다. SYNTHESIS는 "pending 집합"으로 대상을 고르는데, 순서가
			// 뒤바뀌면 방금 이 스윕이 수거해 timely로 확정한 short_code가 여전히 targets에 남아
			// 빈 "확인된 사실"로 재제출되고, 그 재수거가 좋은 해석을 덮어쓴다(2026-09-03 리뷰 C1).
			JobResult swept = collectJob.run();
			if (swept.processed() > 0 || swept.failed() > 0) {
				log.info("배치 제출 전 pending 수거 - {}건 저장, {}건 실패", swept.processed(), swept.failed());
			}
		}
		// 파트 A는 기준선을 인용하지 않는다 - 뷰 스캔(운영 실측 분 단위)을 통째로 건너뛴다.
		Baselines baselines = phase == Phase.FACTS ? EMPTY_BASELINES : loadBaselines();
		List<Map<String, Object>> targets = resolveTargets(phase, timely);

		if (settings.batchTransportEnabled()) {
			// 썸네일 첨부는 사실 추출(파트 A·통합)에만 의미가 있다 - 파트 B는 이미지를 안 보내므로
			// vlm-enabled=true여도 배치로 내려가는 게 정상이다.
			if (thumbnailEnabled && phase != Phase.SYNTHESIS) {
				// 배치 JSONL은 캡션 전용(백필과 동일 - 익일 수거 시점엔 서명 URL이 대부분 만료돼
				// 애초에 첨부하지 않는다). vlm-enabled=true(썸네일 첨부 게이트 on)인데 배치로
				// 내려가면 조용히 이미지 없이 분석돼 온라인과 산출물이 갈린다 - 잡을 죽이지 않고
				// 온라인으로 폴백해 멀티모달 분석을 보존한다(2026-08-11 리뷰 반영).
				log.warn("analytics.analyze-transport=batch인데 vlm-enabled=true - 배치는 캡션 전용이라"
						+ " 온라인 경로로 폴백(썸네일 첨부 보존)");
			} else if (batchApi != null) {
				return submitBatch(phase, timely, targets, baselines);
			} else {
				// provider가 배치 미지원(무료 gemini 폴백 등)이면 잡을 죽이지 않고 온라인으로 내려간다
				// (LlmConfig.geminiApi()의 vertex→gemini 폴백과 같은 안전망 원칙).
				log.warn("analytics.analyze-transport=batch인데 GeminiApi가 배치 미지원 - 온라인 경로로 폴백");
			}
		}
		return runOnline(phase, timely, targets, baselines, progress);
	}

	/**
	 * 후보 뷰 조회(재료 포함) + phase별 제외 게이트. 자격(캘린더일 timely·성숙·윈도우)은 뷰 소관,
	 * 제외는 여기 Java diff 소관이다(클래스 상단 주석 참조). 3종 phase가 게이트 판정 하나만 다르므로
	 * 후보 → 필터 루프는 phase가 고른 {@link Predicate}<short_code> 하나로 수렴한다(M6).
	 *
	 * <p>2026-09-03 phase별 제외:
	 * <ul>
	 * <li>UNIFIED: 행 존재 · 댓글 미분류 (현행)
	 * <li>FACTS: 행 존재만. 상태 불문 - 파트 A만 있는 행도 다시 만들지 않는다.
	 *     댓글 게이트는 걸지 않는다(파트 A는 댓글 분포를 입력으로 쓰지 않는다).
	 * <li>SYNTHESIS: "후보 ∩ pending 집합"이라는 포함 집합으로 A 행 부재와 B 완료를 한 번에
	 *     처리한다(부분 인덱스로 좁혀진 집합이라 통짜 로드가 싸다). 여기에 댓글 게이트를 뺀다.
	 * </ul>
	 *
	 * @return 후보 행 목록. 키는 short_code·account_handle·caption·content_type·thumbnail_url·
	 *         views·likes·comments·ad_marked (하위 조립이 이 이름에 의존).
	 */
	private List<Map<String, Object>> resolveTargets(Phase phase, boolean timely) {
		List<Map<String, Object>> candidates = new ArrayList<>();
		org.springframework.jdbc.core.RowCallbackHandler collect = rs -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("short_code", rs.getString("short_code"));
			row.put("account_handle", rs.getString("account_handle"));
			row.put("caption", rs.getString("caption"));
			row.put("content_type", rs.getString("content_type"));
			row.put("thumbnail_url", rs.getString("thumbnail_url"));
			row.put("views", rs.getObject("views"));
			row.put("likes", rs.getObject("likes"));
			row.put("comments", rs.getObject("comments"));
			row.put("ad_marked", rs.getObject("ad_marked"));
			candidates.add(row);
		};
		if (phase == Phase.FACTS) {
			raw.query(FACT_CANDIDATES_SQL, collect);
		} else {
			raw.query(CANDIDATES_SQL, collect, timely);
		}

		Predicate<String> keep = switch (phase) {
			case FACTS -> {
				Set<String> analyzed = analyzedShortCodes();
				yield shortCode -> !analyzed.contains(shortCode);
			}
			case SYNTHESIS -> {
				Set<String> factsOnly = new HashSet<>(analysis.queryForList(
						"SELECT short_code FROM content_analyses WHERE metric_timeliness = 'pending'",
						String.class));
				Set<String> commentBlocked = commentBlockedShortCodes();
				yield shortCode -> factsOnly.contains(shortCode) && !commentBlocked.contains(shortCode);
			}
			case UNIFIED -> {
				Set<String> analyzed = analyzedShortCodes();
				Set<String> commentBlocked = commentBlockedShortCodes();
				yield shortCode -> !analyzed.contains(shortCode) && !commentBlocked.contains(shortCode);
			}
		};

		List<Map<String, Object>> targets = new ArrayList<>();
		for (Map<String, Object> row : candidates) {
			if (keep.test((String) row.get("short_code"))) {
				targets.add(row);
			}
		}
		return targets;
	}

	/** content_analyses 전체 short_code 집합 - FACTS의 '행 존재' 제외·UNIFIED의 '이미 분석됨' 제외가
	 * 공유한다(M6). 후보 수만·분석 누적 8만 스케일이라 통짜 로드가 충분히 싸다. */
	private Set<String> analyzedShortCodes() {
		return new HashSet<>(analysis.queryForList("SELECT short_code FROM content_analyses", String.class));
	}

	/** 댓글이 미러됐는데 분류가 아직인 short_code - 댓글 인사이트 입력이 미완이라 보류(기존 게이트
	 * 유지). UNIFIED·SYNTHESIS가 공유한다(M6). */
	private Set<String> commentBlockedShortCodes() {
		return new HashSet<>(analysis.queryForList("""
				SELECT DISTINCT m.short_code FROM content_comments m
				WHERE NOT EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = m.short_code)""",
				String.class));
	}

	/** content_batch_jobs.kind - 수거 잡이 응답 스키마를 고르는 값(§4-5). */
	private static String kindOf(Phase phase) {
		return switch (phase) {
			case UNIFIED -> "analyze";
			case FACTS -> "facts";
			case SYNTHESIS -> "synthesis";
		};
	}

	/** 배치·업로드 표시 이름 접두사 - GCS 콘솔에서도 단계를 구분할 수 있게 한다. */
	private static String namePrefixOf(Phase phase) {
		return switch (phase) {
			case UNIFIED -> "hypenow-analyze";
			case FACTS -> "hypenow-facts";
			case SYNTHESIS -> "hypenow-synth";
		};
	}

	/**
	 * 배치 전송 제출 - JSONL 라인 조립은 GeminiBackfillRunner와 공유하는 {@link GeminiBatchLines}
	 * 재사용. 제출 전 pending 잔여 수거는 호출자({@link #runQuery})가 resolveTargets보다 먼저
	 * 끝내둔다(2026-09-03 리뷰 C1 - SYNTHESIS의 대상 선정이 pending 집합에 의존하므로, 방금
	 * 수거되어 timely로 확정된 short_code가 여기 도달하기 전에 이미 빠져 있어야 한다).
	 */
	private JobResult submitBatch(Phase phase, boolean timely, List<Map<String, Object>> targets,
			Baselines baselines) {
		if (targets.isEmpty()) {
			log.info("배치 제출 대상 없음 - 제출 생략 (phase={}, timely={})", phase, timelyLogValue(phase, timely));
			return new JobResult(0, 0, false);
		}
		// 파트 B는 저장된 사실을 프롬프트에 실어야 한다 - 콘텐츠마다 조회하면 제출이 DB 왕복에
		// 잠기므로 pending 행 전량을 1회 조회로 받아 둔다(기준선 로딩과 같은 이유).
		Map<String, Map<String, Object>> storedFacts = phase == Phase.SYNTHESIS
				? StoredFacts.loadPending(analysis) : Map.of();
		if (phase == Phase.SYNTHESIS) {
			// C1 2차 방어선: resolveTargets가 고른 pending 집합과 이 storedFacts 조회 사이의 좁은
			// 창에서도 어긋날 수 있다 - 빈 "확인된 사실"로 내보내는 대신 걸러 다음 실행에 맡긴다.
			targets = requireStoredFacts(targets, storedFacts);
			if (targets.isEmpty()) {
				log.info("배치 제출 대상 없음 - 사실 누락으로 전량 제외 (phase={}, timely={})", phase, timely);
				return new JobResult(0, 0, false);
			}
		}
		// 청크 분할(2026-08-31): 대상 전량을 배치 1건으로 밀면 sidecar_jsonl 한 컬럼에 수십 MB가
		// 들어가고 Vertex 배치 파일 한도에도 걸린다(백로그 일괄 개방 대비). 수거는 배치 행 단위라
		// ContentBatchCollectJob은 무변경이다.
		int chunkSize = settings.batchChunkSize();
		int submitted = 0;
		int chunks = 0;
		for (int from = 0; from < targets.size(); from += chunkSize) {
			List<Map<String, Object>> chunk =
					targets.subList(from, Math.min(from + chunkSize, targets.size()));
			// 업로드 이름은 청크마다 유일해야 한다 - 실구현(VertexHttpApi)의 GCS 객체 경로가
			// displayName 그대로라, 같은 이름이면 뒤 청크가 앞 청크 입력 파일을 덮어쓴다
			// (2026-08-31 운영 실발생 - 3,000건 배치가 795건 결과·전원 사이드카 매칭 실패).
			submitOneChunk(phase, timely, chunk, baselines, storedFacts,
					"%s-%d-c%d".formatted(namePrefixOf(phase), System.currentTimeMillis(), chunks));
			submitted += chunk.size();
			chunks++;
		}
		log.info("분석 배치 제출 완료 - phase={}, 총 {}건, 청크 {}개(상한 {}), timely={}",
				phase, submitted, chunks, chunkSize, timelyLogValue(phase, timely));
		return new JobResult(submitted, 0, false);
	}

	/**
	 * SYNTHESIS 대상 중 저장된 사실이 없는 short_code를 제거한다(2026-09-03 리뷰 C1). 파트 B
	 * 프롬프트의 전제는 "A 행이 존재한다"이므로, 빈 사실로 내보내 좋은 기존 해석을 덮어쓰는 대신
	 * 여기서 걸러 다음 실행에서 자연 재대상되게 한다.
	 */
	private static List<Map<String, Object>> requireStoredFacts(List<Map<String, Object>> targets,
			Map<String, Map<String, Object>> storedFacts) {
		List<Map<String, Object>> kept = new ArrayList<>();
		List<String> dropped = new ArrayList<>();
		for (Map<String, Object> row : targets) {
			String shortCode = (String) row.get("short_code");
			if (storedFacts.containsKey(shortCode)) {
				kept.add(row);
			} else {
				dropped.add(shortCode);
			}
		}
		if (!dropped.isEmpty()) {
			log.warn("SYNTHESIS 대상인데 저장된 사실이 없어 제외 - {}건: {}", dropped.size(), dropped);
		}
		return kept;
	}

	/** FACTS는 timely 개념이 없다 - 로그에 timely=false로 오인 표기되지 않게 n/a로 표기한다(I3). */
	private static Object timelyLogValue(Phase phase, boolean timely) {
		return phase == Phase.FACTS ? "n/a" : timely;
	}

	private void submitOneChunk(Phase phase, boolean timely, List<Map<String, Object>> targets,
			Baselines baselines, Map<String, Map<String, Object>> storedFacts, String uploadName) {
		BeautyTaxonomy taxonomy = taxonomyLoader.get();
		String system = switch (phase) {
			case UNIFIED -> GeminiContentAnalyzer.instructions(taxonomy);
			case FACTS -> GeminiContentAnalyzer.factsInstructions(taxonomy);
			case SYNTHESIS -> GeminiContentSynthesizer.instructions();
		};
		String model = settings.activeLlmModel();
		StringBuilder jsonl = new StringBuilder();
		StringBuilder sidecar = new StringBuilder();
		for (Map<String, Object> content : targets) {
			String shortCode = (String) content.get("short_code");
			Map<String, Object> row = new LinkedHashMap<>(content);
			// 구 contents 조회 결과에 없던 키 — 프롬프트/사이드카 입력 계약을 그대로 보존한다.
			row.remove("short_code");
			row.remove("thumbnail_url");
			if (phase == Phase.FACTS) {
				// 파트 A는 기준선·지표·댓글 분포를 안 싣는다. 사이드카 키 계약(SIDECAR_KEYS)만
				// 채우면 되므로 timely는 false 고정으로 넣고 수거가 읽지 않는다.
				row.put("timely", false);
				jsonl.append(json.writeValueAsString(
								GeminiBatchLines.factsRequestLine(json, shortCode, row, system)))
						.append('\n');
			} else {
				Baseline b = baselines.withBaseline().get(shortCode);
				if (b == null) {
					Baseline accountAvg = baselines.accountBaseline().get((String) content.get("account_handle"));
					b = accountAvg != null ? accountAvg : EMPTY_BASELINE;
				}
				Map<String, Long> categoryCounts = commentCategoryCounts(shortCode);
				putBaseline(row, b);
				row.put("timely", timely);
				jsonl.append(json.writeValueAsString(phase == Phase.UNIFIED
								? GeminiBatchLines.requestLine(json, shortCode, row, categoryCounts, system)
								// requireStoredFacts가 이미 걸러 storedFacts에 반드시 있다 - 파트 B
								// 프롬프트를 빈 "확인된 사실"로 내보내는 사고를 막는다(2026-09-03 C1).
								: GeminiBatchLines.synthesisRequestLine(json, shortCode, row, categoryCounts,
										storedFacts.get(shortCode), system)))
						.append('\n');
			}
			sidecar.append(json.writeValueAsString(GeminiBatchLines.sidecarLine(json, shortCode, row)))
					.append('\n');
		}
		String fileName = batchApi.uploadFile(jsonl.toString().getBytes(StandardCharsets.UTF_8), uploadName);
		String batchName = batchApi.createBatch(model, fileName, uploadName);
		// 사이드카는 로컬 파일이 아니라 DB 컬럼에 보관한다 — analytics 컨테이너에는 쓰기 가능한
		// 볼륨이 없어(deploy/compose.yaml), 제출~수거 사이에 배포·컨테이너 교체가 끼면 로컬 파일은
		// 유실되고 pending 행이 영원히 pending으로 남는 좀비가 된다(리뷰 지적, 08-11).
		analysis.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, status, sidecar_jsonl, kind)
				VALUES (?, ?, ?, 'pending', ?, ?)""",
				batchName, phase == Phase.FACTS ? false : timely, targets.size(),
				sidecar.toString(), kindOf(phase));
		log.info("분석 배치 청크 제출 - batch={}, kind={}, {}건, timely={}",
				batchName, kindOf(phase), targets.size(), timelyLogValue(phase, timely));
	}

	/** 기준선 10키를 프롬프트/사이드카 입력 맵에 싣는다 - 배치 제출 경로 공용. */
	private static void putBaseline(Map<String, Object> row, Baseline b) {
		row.put("recent_reels_avg_views", b.recentReelsAvgViews());
		row.put("rank_in_recent_reels", b.rankInRecentReels());
		row.put("recent_reels_count", b.recentReelsCount());
		row.put("recent_contents_count", b.recentContentsCount());
		row.put("recent12_avg_engagement_rate", b.recent12AvgEngagementRate());
		row.put("recent12_avg_like_count", b.recent12AvgLikeCount());
		row.put("recent12_avg_comment_count", b.recent12AvgCommentCount());
		row.put("category_top_percentile", b.categoryTopPercentile());
		row.put("category_avg_views", b.categoryAvgViews());
		row.put("category_sample_size", b.categorySampleSize());
	}

	/** 댓글 분류 분포 - 온라인·배치 경로가 같은 쿼리를 쓴다(프롬프트 근거가 갈리지 않게). */
	private Map<String, Long> commentCategoryCounts(String shortCode) {
		Map<String, Long> counts = new LinkedHashMap<>();
		analysis.query("""
				SELECT ai_category, count(*) AS cnt FROM comment_classifications
				WHERE short_code = ? GROUP BY ai_category""",
				rs -> {
					counts.put(rs.getString(1), rs.getLong(2));
				}, shortCode);
		return counts;
	}

	private JobResult runOnline(Phase phase, boolean timely, List<Map<String, Object>> targets,
			Baselines baselines, ProgressReporter progress) {
		String model = settings.activeLlmModel();
		// 파트 B 온라인 경로도 저장된 사실이 필요하다 - 배치와 같은 이유로 1회 조회.
		Map<String, Map<String, Object>> storedFacts = phase == Phase.SYNTHESIS
				? StoredFacts.loadPending(analysis) : Map.of();
		// C1 2차 방어선 - 배치 경로(submitBatch)와 동형. resolveTargets의 pending 집합과 이
		// storedFacts 조회 사이에 어긋나면 빈 "확인된 사실"로 해석해 좋은 기존 결과를 덮어쓸 수
		// 있다 - 걸러서 다음 실행에 맡긴다. 아래 태스크 람다가 캡처할 수 있게 final 변수로 고정한다.
		List<Map<String, Object>> resolvedTargets =
				phase == Phase.SYNTHESIS ? requireStoredFacts(targets, storedFacts) : targets;
		AtomicInteger processedCount = new AtomicInteger();
		AtomicInteger failedCount = new AtomicInteger();
		AtomicBoolean quotaExhausted = new AtomicBoolean();
		progress.report(0, 0, resolvedTargets.size());

		// 대상은 제출 순서(=쿼리의 최신순)를 유지한 채 병렬 처리한다 — 고정 크기 풀의 작업 큐는
		// FIFO라 "최신 수집분부터"(썸네일 서명 URL 생존 우선순위, B3) 의도는 유지되고 완료
		// 순서만 동시성 때문에 섞인다. 병렬도는 app_setting(analytics.analyze-concurrency,
		// 기본 8)으로 재배포 없이 조정 가능 — Vertex는 RPM 페이싱이 없어(DSQ) 여유가 있다.
		List<Callable<Void>> tasks = new ArrayList<>();
		for (Map<String, Object> content : resolvedTargets) {
			String shortCode = (String) content.get("short_code");
			tasks.add(() -> {
				if (quotaExhausted.get()) {
					return null; // 이미 쿼타 소진 — 남은 큐는 추가 429를 만들지 않도록 LLM 호출 없이 스킵
				}
				try {
					switch (phase) {
						case UNIFIED -> analyzeOne(content, model, baselines.withBaseline(),
								baselines.accountBaseline(), timely);
						case FACTS -> analyzeFactsOne(content, model);
						case SYNTHESIS -> synthesizeOne(content, model, baselines.withBaseline(),
								baselines.accountBaseline(), storedFacts, timely);
					}
					int p = processedCount.incrementAndGet();
					progress.report(p, failedCount.get(), resolvedTargets.size());
				} catch (com.celfit.analytics.llm.LlmQuotaExhaustedException e) {
					// 일 한도 소진 - 에러가 아닌 이월: 남은 대상은 다음 실행에서 자연 재대상 (07-18
					// 확정, 병렬화 후에도 유지). 이미 진행 중이던 다른 작업은 강제 취소하지 않고
					// 완료시킨다 - 콜 자체가 짧아(초 단위) 취소로 얻는 이득보다 부분 상태 복잡도가 크다.
					quotaExhausted.set(true);
					log.warn("LLM 일 한도 소진 감지 - {} 스킵(이월), 이후 미착수 대상도 스킵됨 (phase={})",
							shortCode, phase);
				} catch (QuietFailure e) {
					// 원인은 이미 QuietFailure를 던진 지점에서 warn으로 남겼다 - 스택트레이스 없이
					// 실패로만 집계한다(M5·M9 - 파트 A null 속성 응답, 파트 B UPDATE 0행).
					int f = failedCount.incrementAndGet();
					log.warn("{} (phase={})", e.getMessage(), phase);
					progress.report(processedCount.get(), f, resolvedTargets.size());
				} catch (Exception e) {
					int f = failedCount.incrementAndGet();
					log.error("analysis failed for {} - 다음 실행에서 재대상 (phase={})", shortCode, phase, e);
					progress.report(processedCount.get(), f, resolvedTargets.size());
				}
				return null;
			});
		}

		int concurrency = Math.max(1, settings.analyzeConcurrency());
		try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
			pool.invokeAll(tasks);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("분석 배치가 인터럽트됨", e);
		}

		int processed = processedCount.get();
		int failed = failedCount.get();
		boolean carriedOver = quotaExhausted.get();
		// 풀 종료 후 최종 수치로 한 번 더 보고 — 동시 완료 시 마지막 개별 report 호출이 진짜
		// 최종값이라는 보장이 없어, 이게 없으면 어드민 진행률 UI가 부정확한 값으로 끝날 수 있다.
		progress.report(processed, failed, resolvedTargets.size());
		log.info("analysis complete (phase={}, {} contents, {} failed, quota carried over={})",
				phase, processed, failed, carriedOver);
		return new JobResult(processed, failed, carriedOver);
	}

	// 기준선 두 종을 통째로 로드한다 — 뷰 평가가 운영 실측 분 단위(07-19, 27k 기준 4.5분)라
	// 건당 조회를 반복하면 배치가 뷰 스캔에 잠긴다. 1회 평가 후 메모리 맵 조회로 대체.
	// PG 타입이 numeric·bigint·smallint로 섞여 있어 전부 BigDecimal로 읽어 변환 (기존 관용구).
	private Baselines loadBaselines() {
		// ① 계정 평균(account_handle 키) — 최근창 밖 후보에 붙일 앵커. rank는 계정 단위가 아니라 null.
		Map<String, Baseline> accountBaseline = new LinkedHashMap<>();
		raw.query("""
				SELECT account_handle, recent_reels_avg_views, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate,
				       recent12_avg_like_count, recent12_avg_comment_count,
				       category_top_percentile, category_avg_views, category_sample_size
				FROM v_analysis_account_baseline""",
				rs -> {
					accountBaseline.put(rs.getString(1), new Baseline(
							longOf(rs.getBigDecimal(2)), null, intOf(rs.getBigDecimal(3)),
							intOf(rs.getBigDecimal(4)), rs.getBigDecimal(5),
							longOf(rs.getBigDecimal(6)), longOf(rs.getBigDecimal(7)),
							intOf(rs.getBigDecimal(8)), longOf(rs.getBigDecimal(9)), longOf(rs.getBigDecimal(10))));
				});
		// ② 콘텐츠 키 기준선(최근창 안 게시물만, rank 포함) — 있으면 계정 평균보다 우선.
		Map<String, Baseline> withBaseline = new LinkedHashMap<>();
		raw.query("""
				SELECT short_code, recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate,
				       recent12_avg_like_count, recent12_avg_comment_count,
				       category_top_percentile, category_avg_views, category_sample_size
				FROM v_analysis_baseline""",
				rs -> {
					withBaseline.put(rs.getString(1), new Baseline(
							longOf(rs.getBigDecimal(2)), intOf(rs.getBigDecimal(3)), intOf(rs.getBigDecimal(4)),
							intOf(rs.getBigDecimal(5)), rs.getBigDecimal(6),
							longOf(rs.getBigDecimal(7)), longOf(rs.getBigDecimal(8)),
							intOf(rs.getBigDecimal(9)), longOf(rs.getBigDecimal(10)), longOf(rs.getBigDecimal(11))));
				});
		return new Baselines(accountBaseline, withBaseline);
	}

	/** content는 후보 뷰가 준 행 — 미러(contents) 재조회 없음(2026-08-31 미러 의존 제거). */
	private void analyzeOne(Map<String, Object> content, String model,
			Map<String, Baseline> withBaseline, Map<String, Baseline> accountBaseline, boolean timely) {
		String shortCode = (String) content.get("short_code");
		// 최근창 안이면 콘텐츠 키 기준선(rank 포함), 밖이면 계정 평균(rank null) 폴백 (07-20 스코프 확장).
		// 계정 집계도 없는 이례적 경우(원본 스키마 스큐 등)엔 전부 null — 프롬프트가 앵커 없이 절제 처리.
		Baseline b = withBaseline.get(shortCode);
		if (b == null) {
			Baseline accountAvg = accountBaseline.get((String) content.get("account_handle"));
			b = accountAvg != null ? accountAvg : EMPTY_BASELINE;
		}
		Map<String, Long> categoryCounts = commentCategoryCounts(shortCode);
		// 캡션 주·썸네일 보조: 썸네일은 게이트 on + 프리체크 생존일 때만 첨부, 만료·off여도 캡션으로 5종 산출.
		// 통합 1콜(속성+종합 — 07-18 확정) 예외(일시 장애)는 기존대로 콘텐츠 실패 → 다음 실행 재대상.
		String caption = (String) content.get("caption");
		String thumbnailUrl = (String) content.get("thumbnail_url");
		boolean attachThumbnail = thumbnailEnabled && thumbnailUrl != null && thumbnailAlive.test(thumbnailUrl);
		if (thumbnailEnabled && thumbnailUrl != null && !attachThumbnail) {
			log.info("썸네일 만료/접근 불가 — 캡션만으로 속성 분석: {}", shortCode);
		}
		boolean hasCaption = caption != null && !caption.isBlank();
		Map<String, Object> baselineForPrompt = PromptBaseline.of(b);
		ContentInsightPort.ContentInsight result = insight.analyze(new ContentToAnalyze(shortCode,
				(String) content.get("account_handle"), caption,
				(String) content.get("content_type"), (Long) content.get("views"),
				(Long) content.get("likes"), (Long) content.get("comments"),
				baselineForPrompt, categoryCounts, (Boolean) content.get("ad_marked")),
				attachThumbnail ? thumbnailUrl : null);
		// 캡션도 썸네일도 없으면 속성 근거 입력이 없다 — 통합 콜이 돌려줘도 폐기하고 속성 컬럼 NULL 유지.
		ContentAttributes attrs = hasCaption || attachThumbnail ? result.attributes() : null;
		Synthesis s = result.synthesis();
		// content_analyses는 불변(INSERT만)이라 빈 결과가 저장되면 영구 고정 + 재분석 대상에서도 제외된다.
		// 저장 전에 실패 처리해 콘텐츠 단위 try/catch가 skip → 다음 실행에서 재대상되게 한다.
		if (s.aiContentSummary() == null || s.aiContentSummary().isBlank()) {
			throw new IllegalStateException("종합 텍스트가 비어 있음: " + shortCode);
		}
		// 분류 대상으로 판정됐으나 복구 후에도 대분류를 못 얻은 경우: 분석은 temperature 0 결정론이라
		// 같은 입력을 재실행해도 동일 결과 → 옛 self-heal(행 미기록·재대상)은 무한 재시도로 영영
		// 완료되지 않고 매 실행 LLM 호출만 태웠다(운영 실측 재대상 루프). 미분류로 **종결 저장**해
		// 루프를 끊는다 — 불변식 'main_category null ⇒ 서빙에서 제외'는 그대로 보존(is_beauty=false라
		// 랭킹·인플루언서 상세에서 제외), 서빙 계층 무변경. 진짜 일시 실패(빈 종합·파싱 오류)는 위에서
		// 여전히 throw→재대상으로 self-heal한다. (설계 2026-07-20 §3-3 개정: 결정론 케이스는 종결.
		// 2026-08-31 축 일반화로 조건을 isBeauty→isRelevant로 옮겼다 — F&B도 같은 처방이 필요하다.)
		if (attrs != null && Boolean.TRUE.equals(attrs.isRelevant()) && attrs.mainCategory() == null) {
			log.info("분류 대상이나 대분류 미도출 — 미분류로 종결 저장(재시도 루프 방지): {}", shortCode);
			attrs = attrs.asUnclassified();
		}
		// V33 마킹 분기(07-20 개정): 제때 가드를 충족하면 timely, 윈도우 경로로만 들어온 늦크롤은 late_backfill.
		ContentAnalysisWriter.insert(analysis, json, shortCode, model, b, attrs, s, false,
				timely ? "timely" : "late_backfill");
	}

	/**
	 * 파트 A 온라인 1건 - 캡션(+썸네일 게이트 on이면 생존 썸네일)만 보고 사실을 추출해 pending으로 저장한다.
	 * 캡션도 썸네일도 없으면 속성 근거가 없으므로 폐기하고 컬럼 NULL로 행만 만든다(통합 경로와 같은 규칙).
	 */
	private void analyzeFactsOne(Map<String, Object> content, String model) {
		String shortCode = (String) content.get("short_code");
		String caption = (String) content.get("caption");
		String thumbnailUrl = (String) content.get("thumbnail_url");
		boolean attachThumbnail = thumbnailEnabled && thumbnailUrl != null && thumbnailAlive.test(thumbnailUrl);
		if (thumbnailEnabled && thumbnailUrl != null && !attachThumbnail) {
			log.info("썸네일 만료/접근 불가 - 캡션만으로 사실 추출: {}", shortCode);
		}
		boolean hasCaption = caption != null && !caption.isBlank();
		ContentAttributes attrs = factsPort.extractFacts(new ContentToAnalyze(shortCode,
				(String) content.get("account_handle"), caption, (String) content.get("content_type"),
				null, null, null, Map.of(), Map.of(), (Boolean) content.get("ad_marked")),
				attachThumbnail ? thumbnailUrl : null);
		if (!hasCaption && !attachThumbnail) {
			attrs = null;
		} else if (attrs == null) {
			// 통합 경로(analyzeOne)와 같은 null 가드(M5) - 여기 도달했으면 hasCaption||attachThumbnail로
			// 포트를 실제로 호출했는데 null이 돌아온 예기치 않은 케이스다. 빈 사실 행을 성공으로
			// 오기록하면 다음 실행에서 재시도되지 않으므로 실패로 집계한다.
			throw new QuietFailure("파트 A 포트가 null 속성을 반환 - 실패로 집계(재시도): " + shortCode);
		} else if (Boolean.TRUE.equals(attrs.isRelevant()) && attrs.mainCategory() == null) {
			// 통합 경로와 같은 처방 - temperature 0 결정론이라 재대상해도 결과가 같다(무한 루프 방지).
			log.info("분류 대상이나 대분류 미도출 - 미분류로 종결 저장(재시도 루프 방지): {}", shortCode);
			attrs = attrs.asUnclassified();
		}
		int inserted = ContentAnalysisWriter.insertFacts(analysis, json, shortCode, model, attrs);
		if (inserted == 0) {
			// ON CONFLICT DO NOTHING - 이미 존재하는 행(배치 스윕과의 경합 등). insertFacts의
			// 멱등 계약대로 조용히 넘어가되, 예상 밖 빈도로 발생하면 보이도록 warn만 남긴다(M8).
			log.warn("파트 A INSERT 0행 - 이미 존재하는 행(ON CONFLICT DO NOTHING): {}", shortCode);
		}
	}

	/**
	 * 파트 B 온라인 1건 - 저장된 사실 + 핀 지표 + 기준선으로 해석 5필드를 만들고 시점을 확정한다.
	 * 빈 종합은 저장하지 않는다 - 저장하면 pending이 풀려 다시 대상이 되지 않는다.
	 */
	private void synthesizeOne(Map<String, Object> content, String model,
			Map<String, Baseline> withBaseline, Map<String, Baseline> accountBaseline,
			Map<String, Map<String, Object>> storedFacts, boolean timely) {
		String shortCode = (String) content.get("short_code");
		Baseline b = withBaseline.get(shortCode);
		if (b == null) {
			Baseline accountAvg = accountBaseline.get((String) content.get("account_handle"));
			b = accountAvg != null ? accountAvg : EMPTY_BASELINE;
		}
		// requireStoredFacts가 이미 걸러 storedFacts에 반드시 있다 - 여기 도달했다는 것 자체가
		// 파트 B 프롬프트의 전제("A 행이 존재한다")를 만족한다는 뜻이다(2026-09-03 C1).
		Synthesis s = synthesisPort.synthesize(new ContentToSynthesize(shortCode,
				(String) content.get("account_handle"), (String) content.get("content_type"),
				(Long) content.get("views"), (Long) content.get("likes"), (Long) content.get("comments"),
				PromptBaseline.of(b), commentCategoryCounts(shortCode),
				storedFacts.get(shortCode)));
		if (s.aiContentSummary() == null || s.aiContentSummary().isBlank()) {
			throw new IllegalStateException("해석 문구가 비어 있음: " + shortCode);
		}
		int updated = ContentAnalysisWriter.updateSynthesis(analysis, shortCode, model, b, s,
				timely ? "timely" : "late_backfill");
		if (updated == 0) {
			// 수거 경로(GeminiBatchLines.processSynthesisResultLine)와 같은 처방(M9) - 그 사이 행이
			// 사라진 경우는 스택트레이스를 남길 예외적 버그가 아니라 경합으로 벌어지는 정상 범주의
			// 실패다. warn으로만 집계하고 다음 실행이 재대상하게 둔다.
			throw new QuietFailure("해석 UPDATE 0행 - 그 사이 행이 사라짐: " + shortCode);
		}
	}

	/**
	 * 스택트레이스 없이 warn으로만 집계할 실패 신호 - 이미 원인 로그를 남긴(또는 남길) 예상 범주의
	 * 실패(M5의 파트 A null 속성 응답, M9의 파트 B UPDATE 0행)가 공유한다. runOnline의 전용
	 * catch 절이 처리한다.
	 */
	private static final class QuietFailure extends RuntimeException {
		QuietFailure(String message) {
			super(message);
		}
	}

	private static Long longOf(java.math.BigDecimal v) {
		return v == null ? null : v.longValueExact();
	}

	private static Integer intOf(java.math.BigDecimal v) {
		return v == null ? null : v.intValueExact();
	}
}
