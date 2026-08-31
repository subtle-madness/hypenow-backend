package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.BeautyTaxonomy;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.ContentInsightPort;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.llm.GeminiContentAnalyzer;
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
 * 콘텐츠 분석 배치 (스펙 §6). 분석 시점 고정·불변 — INSERT만, 재분석 없음.
 * 대상: raw 후보 뷰(v_analysis_candidates)의 후보 중 미분석 AND (댓글 없음 OR 분류 완료) —
 * 자격(캘린더일 timely·성숙·윈도우)은 뷰 소관(07-28 정합), 제외는 여기 Java diff 소관.
 * timely(run())와 late_backfill(runLateBackfill())은 서로 다른 진입점 — 예산·스케줄이 별도라
 * 백필 후보가 몰려도 매일 갱신돼야 할 timely 분석이 밀리지 않는다(2026-07-23 설계, 뷰의
 * timely 컬럼으로 서로소 분할).
 * 속성 분석은 캡션 주·썸네일 보조 (2026-07-14 캡션 분류 스펙) — 썸네일 만료여도 캡션으로 5종 산출.
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
	private static final String CANDIDATES_SQL = """
			SELECT short_code
			FROM v_analysis_candidates
			WHERE timely = ?
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
	// 배치 전송(2026-08-11, Vertex 배치 50% 할인) — batchApi가 null이면 배치 미지원 프로바이더라
	// transport=batch여도 온라인으로 폴백한다(LlmConfig.geminiApi()의 무료 gemini 폴백과 동형 안전망).
	private final GeminiBatchApi batchApi;
	private final BeautyTaxonomyLoader taxonomyLoader;
	private final ContentBatchCollectJob collectJob;

	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive,
			ProgressReporter reporter, ProgressReporter backfillReporter) {
		this(rawJdbcTemplate, analysisDataSource, insight, settings, thumbnailEnabled, thumbnailAlive,
				reporter, backfillReporter, null, null);
	}

	/**
	 * @param batchApi 배치 전송 제출·상태 확인용 — null이면 배치 미지원 프로바이더(온라인 폴백).
	 * @param taxonomyLoader 배치 요청의 시스템 프롬프트 조립용(뷰티 분류표) — batchApi가 null이 아닐 때만 쓰인다.
	 */
	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive,
			ProgressReporter reporter, ProgressReporter backfillReporter,
			GeminiBatchApi batchApi, BeautyTaxonomyLoader taxonomyLoader) {
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
		this.collectJob = new ContentBatchCollectJob(analysisDataSource, batchApi, taxonomyLoader, settings);
	}

	/** raw v_analysis_account_baseline·v_analysis_baseline 1회 로딩 결과 — run()·runLateBackfill() 공유. */
	private record Baselines(Map<String, Baseline> accountBaseline, Map<String, Baseline> withBaseline) {}

	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>후보 뷰의 timely 후보 전량(LIMIT 없음 — 실질 상한은 LLM 429 quota).
	 */
	public JobResult run() {
		return runQuery(true, reporter);
	}

	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>후보 뷰의 NOT timely 후보(= 최근 N개 윈도우 안 늦크롤) 전량(LIMIT 없음).
	 * run()과 상호 배타 — 같은 뷰의 timely 컬럼으로 서로소 분할이라 같은 short_code가
	 * 두 진입점에 동시에 잡히지 않는다.
	 */
	public JobResult runLateBackfill() {
		return runQuery(false, backfillReporter);
	}

	private JobResult runQuery(boolean timely, ProgressReporter progress) {
		Baselines baselines = loadBaselines();
		List<String> targets = resolveTargets(timely);

		if (settings.batchTransportEnabled()) {
			if (thumbnailEnabled) {
				// 배치 JSONL은 캡션 전용(백필과 동일 — 익일 수거 시점엔 서명 URL이 대부분 만료돼
				// 애초에 첨부하지 않는다). vlm-enabled=true(썸네일 첨부 게이트 on)인데 배치로
				// 내려가면 조용히 이미지 없이 분석돼 온라인과 산출물이 갈린다 — 잡을 죽이지 않고
				// 온라인으로 폴백해 멀티모달 분석을 보존한다(2026-08-11 리뷰 반영).
				log.warn("analytics.analyze-transport=batch인데 vlm-enabled=true — 배치는 캡션 전용이라"
						+ " 온라인 경로로 폴백(썸네일 첨부 보존)");
			} else if (batchApi != null) {
				return submitBatch(timely, targets, baselines);
			} else {
				// provider가 배치 미지원(무료 gemini 폴백 등)이면 잡을 죽이지 않고 온라인으로 내려간다
				// (LlmConfig.geminiApi()의 vertex→gemini 폴백과 같은 안전망 원칙).
				log.warn("analytics.analyze-transport=batch인데 GeminiApi가 배치 미지원 — 온라인 경로로 폴백");
			}
		}
		return runOnline(timely, targets, baselines, progress);
	}

	/**
	 * 후보 뷰 조회 + 3종 제외 게이트(이미 분석됨·댓글 미분류·미러 미도달) — 온라인·배치 제출 양쪽이
	 * 공유한다(2026-08-11). 자격(캘린더일 timely·성숙·윈도우)은 뷰 소관, 제외는 여기 Java diff 소관
	 * (클래스 상단 주석 참조).
	 */
	private List<String> resolveTargets(boolean timely) {
		List<String> candidates = new ArrayList<>();
		raw.query(CANDIDATES_SQL, rs -> {
			candidates.add(rs.getString(1));
		}, timely);
		// analysis 쪽 제외 셋 3종 — 후보 수만·분석 누적 8만 스케일이라 통짜 로드가 충분히 싸다.
		Set<String> analyzed = new HashSet<>(
				analysis.queryForList("SELECT short_code FROM content_analyses", String.class));
		// 댓글이 미러됐는데 분류가 아직인 콘텐츠는 댓글 인사이트 입력이 미완이라 보류(기존 게이트 유지)
		Set<String> commentBlocked = new HashSet<>(analysis.queryForList("""
				SELECT DISTINCT m.short_code FROM content_comments m
				WHERE NOT EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = m.short_code)""",
				String.class));
		// 라이브 후보 뷰와 미러(전날 19:30 스냅샷) 간극 가드 — 미러에 아직 없는 후보를 analyzeOne이
		// 조회 실패(실패 카운트 오염)로 만들지 않고 스킵한다. 다음 미러 후 자연 재대상.
		Set<String> mirrored = new HashSet<>(
				analysis.queryForList("SELECT short_code FROM contents", String.class));
		List<String> targets = new ArrayList<>();
		int mirrorMissing = 0;
		for (String shortCode : candidates) {
			if (analyzed.contains(shortCode) || commentBlocked.contains(shortCode)) {
				continue;
			}
			if (!mirrored.contains(shortCode)) {
				mirrorMissing++;
				continue;
			}
			targets.add(shortCode);
		}
		if (mirrorMissing > 0) {
			log.info("미러 부재 후보 {}건 스킵 — 다음 미러 후 자연 재대상", mirrorMissing);
		}
		return targets;
	}

	/**
	 * 배치 전송 제출 — JSONL 라인 조립은 GeminiBackfillRunner와 공유하는 {@link GeminiBatchLines}
	 * 재사용. 제출 전 pending 잔여를 먼저 수거해 중복 제출을 완화한다(전날 미수거분 회수 — 이미
	 * 분석됨 diff·ON CONFLICT DO NOTHING이 이중 안전장치라 설령 겹쳐도 무해).
	 */
	private JobResult submitBatch(boolean timely, List<String> targets, Baselines baselines) {
		JobResult swept = collectJob.run();
		if (swept.processed() > 0 || swept.failed() > 0) {
			log.info("배치 제출 전 pending 수거 — {}건 저장, {}건 실패", swept.processed(), swept.failed());
		}
		if (targets.isEmpty()) {
			log.info("배치 제출 대상 없음 — 제출 생략 (timely={})", timely);
			return new JobResult(0, 0, false);
		}
		BeautyTaxonomy taxonomy = taxonomyLoader.get();
		String system = GeminiContentAnalyzer.instructions(taxonomy);
		String model = settings.activeLlmModel();
		StringBuilder jsonl = new StringBuilder();
		StringBuilder sidecar = new StringBuilder();
		for (String shortCode : targets) {
			Map<String, Object> content = analysis.queryForMap("""
					SELECT account_handle, caption, content_type, views, likes, comments, ad_marked
					FROM contents WHERE short_code = ?""", shortCode);
			Baseline b = baselines.withBaseline().get(shortCode);
			if (b == null) {
				Baseline accountAvg = baselines.accountBaseline().get((String) content.get("account_handle"));
				b = accountAvg != null ? accountAvg : EMPTY_BASELINE;
			}
			// 댓글 분류 분포 — 온라인 경로(analyzeOne)와 동일 쿼리. 후보 게이트(resolveTargets의
			// commentBlocked 제외)가 "댓글 없음 OR 분류 완료"를 이미 보장하므로 여기 도달한 대상은
			// 빈 분포가 나올 수 없는 구조다(2026-08-11 리뷰 반영 — 이전엔 배치 JSONL이 분포를
			// 항상 비워 보내 프롬프트의 aiCommentInsight 근거가 온라인과 갈렸다).
			Map<String, Long> categoryCounts = new LinkedHashMap<>();
			analysis.query("""
					SELECT ai_category, count(*) AS cnt FROM comment_classifications
					WHERE short_code = ? GROUP BY ai_category""",
					rs -> {
						categoryCounts.put(rs.getString(1), rs.getLong(2));
					}, shortCode);
			// 배치 요청 행 — GeminiBatchLines.requestLine/sidecarLine 둘 다 이 한 맵에서 필요한 키를 뽑는다
			// (백필 러너의 raw 뷰 조인 행과 같은 키 이름 계약). 캡션 단독(백필과 동일 — 썸네일 서명 URL은
			// 익일 수거 시점엔 대부분 만료라 애초에 첨부하지 않는다).
			Map<String, Object> row = new LinkedHashMap<>(content);
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
			row.put("timely", timely);
			jsonl.append(json.writeValueAsString(
							GeminiBatchLines.requestLine(json, shortCode, row, categoryCounts, system)))
					.append('\n');
			sidecar.append(json.writeValueAsString(GeminiBatchLines.sidecarLine(json, shortCode, row)))
					.append('\n');
		}
		String fileName = batchApi.uploadFile(jsonl.toString().getBytes(StandardCharsets.UTF_8), "hypenow-analyze");
		String batchName = batchApi.createBatch(model, fileName, "hypenow-analyze");
		// 사이드카는 로컬 파일이 아니라 DB 컬럼에 보관한다 — analytics 컨테이너에는 쓰기 가능한
		// 볼륨이 없어(deploy/compose.yaml), 제출~수거 사이에 배포·컨테이너 교체가 끼면 로컬 파일은
		// 유실되고 pending 행이 영원히 pending으로 남는 좀비가 된다(리뷰 지적, 08-11). 백필 CLI
		// (GeminiBackfillRunner)는 단일 실행 안에서 submit→collect가 끝나는 일회성 도구라 파일
		// 방식을 그대로 유지한다.
		analysis.update("""
				INSERT INTO content_batch_jobs (batch_name, timely, submitted_count, status, sidecar_jsonl)
				VALUES (?, ?, ?, 'pending', ?)""", batchName, timely, targets.size(), sidecar.toString());
		log.info("분석 배치 제출 완료 — batch={}, {}건, timely={}", batchName, targets.size(), timely);
		return new JobResult(targets.size(), 0, false);
	}

	private JobResult runOnline(boolean timely, List<String> targets, Baselines baselines,
			ProgressReporter progress) {
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

	private static Long longOf(java.math.BigDecimal v) {
		return v == null ? null : v.longValueExact();
	}

	private static Integer intOf(java.math.BigDecimal v) {
		return v == null ? null : v.intValueExact();
	}
}
