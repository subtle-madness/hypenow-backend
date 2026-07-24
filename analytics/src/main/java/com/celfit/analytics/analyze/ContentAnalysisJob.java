package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.ContentInsightPort;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.Synthesis;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 콘텐츠 분석 배치 (스펙 §6). 분석 시점 고정·불변 — INSERT만, 재분석 없음.
 * 대상: 미분석 AND (댓글 없음 OR 분류 완료) AND 게시 후 N일 경과(기본 3 — B3 숙성 가드).
 * timely(run())와 late_backfill(runLateBackfill())은 서로 다른 진입점 — 예산·스케줄이 별도라
 * 백필 후보가 몰려도 매일 갱신돼야 할 timely 분석이 밀리지 않는다(2026-07-23 설계, NOT timely로
 * 상호 배타적).
 * 속성 분석은 캡션 주·썸네일 보조 (2026-07-14 캡션 분류 스펙) — 썸네일 만료여도 캡션으로 5종 산출.
 * 콘텐츠 단위 실패 격리: 한 건 실패는 로그 후 계속 (B2 리뷰 반영).
 */
public class ContentAnalysisJob {

	private static final Logger log = LoggerFactory.getLogger(ContentAnalysisJob.class);
	/** 계정 집계마저 없는 이례적 케이스용 — 전부 null (프롬프트가 앵커 없이 절제 처리). */
	private static final Baseline EMPTY_BASELINE =
			new Baseline(null, null, null, null, null, null, null, null, null, null);

	// 제때 크롤 가드(07-19 정정, 판정식 07-20 보존): 고정 지표가 성숙(+pin일) 스냅샷이면서
	// +(pin+slack)일 안에 잡힌 것만 timely. posted_at·metric_captured_at NULL은 COALESCE로
	// timely=false로 자연 제외.
	private static final String TIMELY_SQL = """
			WITH base AS (
			  SELECT c.short_code, c.metric_captured_at,
			         COALESCE(c.metric_captured_at >= c.posted_at + make_interval(days => ?)
			              AND c.metric_captured_at < c.posted_at + make_interval(days => ?), false) AS timely
			  FROM contents c
			  WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
			    AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
			         OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
			    AND c.posted_at <= now() - make_interval(days => ?)
			)
			SELECT short_code
			FROM base
			WHERE timely
			ORDER BY metric_captured_at DESC NULLS LAST, short_code""";

	// timely 가드를 못 채운 콘텐츠 중 계정별 최근 N개(recent-window) 윈도우 안인 것만 — 늦크롤 백필
	// (07-20 재도입). NOT timely로 timely 쿼리와 상호 배타적(같은 콘텐츠를 두 잡이 동시에 집지 않음).
	// 창 닫힘 게이트(posted_at + (pin+slack)일 <= now())는 윈도우 분기에만 건다 — 제때창이 아직 열려
	// 있는 콘텐츠를 조기에 late_backfill로 분석해버리면, 나중에 진짜 timely 스냅샷이 들어와도
	// content_analyses가 불변이라 영구 오분류된다.
	private static final String LATE_BACKFILL_SQL = """
			WITH base AS (
			  SELECT c.short_code, c.metric_captured_at,
			         COALESCE(c.metric_captured_at >= c.posted_at + make_interval(days => ?)
			              AND c.metric_captured_at < c.posted_at + make_interval(days => ?), false) AS timely
			  FROM contents c
			  WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
			    AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
			         OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
			    AND c.posted_at <= now() - make_interval(days => ?)
			),
			ranked AS (
			  SELECT short_code, posted_at,
			         row_number() OVER (PARTITION BY account_handle
			             ORDER BY posted_at DESC, short_code DESC) AS rn
			  FROM contents
			)
			SELECT short_code
			FROM base
			WHERE NOT timely AND short_code IN (
			  SELECT short_code FROM ranked
			  WHERE rn <= ? AND posted_at <= now() - make_interval(days => ?)
			)
			ORDER BY metric_captured_at DESC NULLS LAST, short_code""";

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final ContentInsightPort insight; // ②속성+③종합 통합 1콜 (07-18 확정)
	private final AnalyticsSettings settings;
	private final boolean thumbnailEnabled; // 썸네일 첨부 게이트 — off여도 캡션 기반 속성은 산출
	private final Predicate<String> thumbnailAlive;
	private final ProgressReporter reporter;
	private final ProgressReporter backfillReporter; // runLateBackfill() 진행률 — run()의 reporter와 별도 JobName
	private final ObjectMapper json = new ObjectMapper();

	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive,
			ProgressReporter reporter, ProgressReporter backfillReporter) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.insight = insight;
		this.settings = settings;
		this.thumbnailEnabled = thumbnailEnabled;
		this.thumbnailAlive = thumbnailAlive;
		this.reporter = reporter;
		this.backfillReporter = backfillReporter;
	}

	/** raw v_analysis_account_baseline·v_analysis_baseline 1회 로딩 결과 — run()·runLateBackfill() 공유. */
	private record Baselines(Map<String, Baseline> accountBaseline, Map<String, Baseline> withBaseline) {}

	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>timely 가드를 충족한 미분석 콘텐츠 전량(LIMIT 없음 — 실질 상한은 LLM 429 quota).
	 */
	public JobResult run() {
		int pinDays = settings.metricPinDays();
		int slackDays = settings.analyzeTimelySlackDays();
		return runQuery(TIMELY_SQL,
				new Object[] {pinDays, pinDays + slackDays, settings.analyzeMaturityDays()},
				true, reporter);
	}

	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>timely 가드는 못 채웠지만 계정별 최근 N개 윈도우 안인 콘텐츠 전량(LIMIT 없음).
	 * run()과 상호 배타적 — 같은 short_code가 두 쿼리에 동시에 잡히지 않는다.
	 */
	public JobResult runLateBackfill() {
		int pinDays = settings.metricPinDays();
		int slackDays = settings.analyzeTimelySlackDays();
		return runQuery(LATE_BACKFILL_SQL,
				new Object[] {pinDays, pinDays + slackDays, settings.analyzeMaturityDays(),
						settings.recentWindow(), pinDays + slackDays},
				false, backfillReporter);
	}

	private JobResult runQuery(String sql, Object[] params, boolean timely, ProgressReporter progress) {
		Baselines baselines = loadBaselines();
		List<String> targets = new ArrayList<>();
		analysis.query(sql, rs -> {
			targets.add(rs.getString(1));
		}, params);
		String model = settings.activeLlmModel();
		AtomicInteger processedCount = new AtomicInteger();
		AtomicInteger failedCount = new AtomicInteger();
		AtomicBoolean quotaExhausted = new AtomicBoolean();
		progress.report(0, 0, targets.size());

		// 대상은 제출 순서(=쿼리의 최신순)를 유지한 채 병렬 처리한다 — 고정 크기 풀의 작업 큐는
		// FIFO라 "최신 수집분부터"(썸네일 서명 URL 생존 우선순위, B3) 의도는 유지되고 완료
		// 순서만 동시성 때문에 섞인다. 병렬도는 app_setting(analytics.analyze-concurrency,
		// 기본 8)으로 재배포 없이 조정 가능 — Vertex는 RPM 페이싱이 없어(DSQ) 여유가 있다.
		List<Callable<Void>> tasks = new ArrayList<>();
		for (String shortCode : targets) {
			tasks.add(() -> {
				if (quotaExhausted.get()) {
					return null; // 이미 쿼타 소진 — 남은 큐는 추가 429를 만들지 않도록 LLM 호출 없이 스킵
				}
				try {
					analyzeOne(shortCode, model, baselines.withBaseline(), baselines.accountBaseline(), timely);
					int p = processedCount.incrementAndGet();
					progress.report(p, failedCount.get(), targets.size());
				} catch (com.celfit.analytics.llm.LlmQuotaExhaustedException e) {
					// 일 한도 소진 — 에러가 아닌 이월: 남은 대상은 다음 실행에서 자연 재대상 (07-18
					// 확정, 병렬화 후에도 유지). 이미 진행 중이던 다른 작업은 강제 취소하지 않고
					// 완료시킨다 — 콜 자체가 짧아(초 단위) 취소로 얻는 이득보다 부분 상태 복잡도가 크다.
					quotaExhausted.set(true);
					log.warn("LLM 일 한도 소진 감지 — {} 스킵(이월), 이후 미착수 대상도 스킵됨", shortCode);
				} catch (Exception e) {
					int f = failedCount.incrementAndGet();
					log.error("analysis failed for {} — 다음 실행에서 재대상", shortCode, e);
					progress.report(processedCount.get(), f, targets.size());
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
		progress.report(processed, failed, targets.size());
		log.info("analysis complete ({} contents, {} failed, quota carried over={})",
				processed, failed, carriedOver);
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
				FROM analytics.v_analysis_account_baseline""",
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
				FROM analytics.v_analysis_baseline""",
				rs -> {
					withBaseline.put(rs.getString(1), new Baseline(
							longOf(rs.getBigDecimal(2)), intOf(rs.getBigDecimal(3)), intOf(rs.getBigDecimal(4)),
							intOf(rs.getBigDecimal(5)), rs.getBigDecimal(6),
							longOf(rs.getBigDecimal(7)), longOf(rs.getBigDecimal(8)),
							intOf(rs.getBigDecimal(9)), longOf(rs.getBigDecimal(10)), longOf(rs.getBigDecimal(11))));
				});
		return new Baselines(accountBaseline, withBaseline);
	}

	private void analyzeOne(String shortCode, String model,
			Map<String, Baseline> withBaseline, Map<String, Baseline> accountBaseline, boolean timely) {
		Map<String, Object> content = analysis.queryForMap("""
				SELECT account_handle, caption, content_type, thumbnail_url, views, likes, comments,
				       ad_marked
				FROM contents WHERE short_code = ?""", shortCode);
		// 최근창 안이면 콘텐츠 키 기준선(rank 포함), 밖이면 계정 평균(rank null) 폴백 (07-20 스코프 확장).
		// 계정 집계도 없는 이례적 경우(원본 스키마 스큐 등)엔 전부 null — 프롬프트가 앵커 없이 절제 처리.
		Baseline b = withBaseline.get(shortCode);
		if (b == null) {
			Baseline accountAvg = accountBaseline.get((String) content.get("account_handle"));
			b = accountAvg != null ? accountAvg : EMPTY_BASELINE;
		}
		Map<String, Long> categoryCounts = new LinkedHashMap<>();
		analysis.query("""
				SELECT ai_category, count(*) AS cnt FROM comment_classifications
				WHERE short_code = ? GROUP BY ai_category""",
				rs -> {
					categoryCounts.put(rs.getString(1), rs.getLong(2));
				}, shortCode);
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
		// 뷰티로 판정됐으나 복구 후에도 대분류를 못 얻은 경우: 분석은 temperature 0 결정론이라 같은
		// 입력을 재실행해도 동일 결과 → 옛 self-heal(행 미기록·재대상)은 무한 재시도로 영영 완료되지
		// 않고 매 실행 LLM 호출만 태웠다(운영 실측 재대상 루프). is_beauty=false로 **종결 저장**해
		// 루프를 끊는다 — 불변식 'main_category null ⇒ 서빙에서 비뷰티'는 그대로 보존(is_beauty=false라
		// 랭킹·인플루언서 상세에서 제외), 서빙 계층 무변경. 진짜 일시 실패(빈 종합·파싱 오류)는 위에서
		// 여전히 throw→재대상으로 self-heal한다. (설계 2026-07-20 §3-3 개정: 결정론 케이스는 종결)
		if (attrs != null && Boolean.TRUE.equals(attrs.isBeauty()) && attrs.mainCategory() == null) {
			log.info("뷰티 판정이나 대분류 미도출 — is_beauty=false로 종결 저장(재시도 루프 방지): {}", shortCode);
			attrs = attrs.asNonBeauty();
		}
		// V33 마킹 분기(07-20 개정): 제때 가드를 충족하면 timely, 윈도우 경로로만 들어온 늦크롤은 late_backfill.
		ContentAnalysisWriter.insert(analysis, json, shortCode, model, b, attrs, s, false,
				timely ? "timely" : "late_backfill");
	}

	private static Long longOf(java.math.BigDecimal v) {
		return v == null ? null : v.longValueExact();
	}

	private static Integer intOf(java.math.BigDecimal v) {
		return v == null ? null : v.intValueExact();
	}
}
