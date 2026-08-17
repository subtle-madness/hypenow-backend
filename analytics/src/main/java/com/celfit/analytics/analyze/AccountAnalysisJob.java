package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AccountCopy;
import com.celfit.analytics.llm.AccountSynthesisPort;
import com.celfit.analytics.llm.AccountToAnalyze;
import com.celfit.analytics.llm.AdSituation;
import com.celfit.analytics.llm.CopyRules;
import com.celfit.analytics.llm.GeminiAccountSynthesizer;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.llm.LlmQuotaExhaustedException;
import com.celfit.analytics.llm.TraitTaxonomy;
import com.celfit.analytics.llm.TraitTaxonomyLoader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 계정 카피 배치 (스펙 §4). content_analyses(불변 1회)와 달리 stale 재분석 —
 * 행은 INSERT로만 쌓고 was/E는 계정별 최신 1행을 읽는다.
 * 대상: ① 분석 없음 → 즉시 ② 최신 행이 구 스키마(perf_summary NULL) → 즉시(07-27 개편 백필)
 *       ③ input_last_posted_at ≠ 미러 last_posted_at(새 게시물, stale)
 *       AND 마지막 분석 후 쿨다운(일) 경과. 계정 단위 실패 격리.
 */
public class AccountAnalysisJob {

	private static final Logger log = LoggerFactory.getLogger(AccountAnalysisJob.class);
	static final int CAPTION_CHARS = 300;

	/**
	 * 계정 카피 대상 자격 정의 — 단일 정본 (07-28 드리프트 재발 방지). run()의 대상 쿼리와
	 * 어드민 대상 카운트(PipelineStatsService.accountTarget)·Claude 버스트 export
	 * (ClaudeBurstRunner.exportAccounts)가 이 문자열을 그대로 이어붙여 써서 세 곳의 판정이
	 * 항상 같은 SQL이 되게 한다. 파라미터는 순서대로 ①쿨다운 일수(accountAnalyzeCooldownDays)
	 * ②카피 버전 게이트({@link CopyRules#VERSION}) 둘 — 이어붙이는 쪽은 이 순서 뒤에 자기
	 * 파라미터(배치 상한 등)를 추가한다.
	 *
	 * <p>{@code latest.copy_version < ?}는 설계 §4 버전 게이트 — 판정 규칙(PerfConfidence)이
	 * 바뀌어 {@link CopyRules#VERSION}이 오르면, 기존 행은 낮은 버전에 머물러 자연 재대상이 된다.
	 */
	public static final String ELIGIBLE_WHERE = """
			LEFT JOIN LATERAL (
			  SELECT a.input_last_posted_at, a.analyzed_at, a.perf_summary, a.copy_version
			  FROM account_analyses a WHERE a.handle = s.handle
			  ORDER BY a.analyzed_at DESC LIMIT 1
			) latest ON true
			WHERE latest.analyzed_at IS NULL
			   OR latest.perf_summary IS NULL  -- 07-27 개편 백필: 구 스키마 행 자연 재대상
			   OR (latest.input_last_posted_at IS DISTINCT FROM s.last_posted_at
			       AND latest.analyzed_at < now() - make_interval(days => ?))
			   OR latest.copy_version < ?  -- 설계 §4: 판정 규칙 버전업 시 낡은 문구 자연 재대상""";

	private final JdbcTemplate analysis;
	private final AccountSynthesisPort port;
	private final AnalyticsSettings settings;
	private final ProgressReporter reporter;
	private final TraitTaxonomyLoader traitLoader;
	private final ObjectMapper json = new ObjectMapper();
	// 배치 전송(2026-08-17) — batchApi가 null이면 배치 미지원 프로바이더라 transport=batch여도
	// 온라인으로 폴백한다(ContentAnalysisJob과 동형 안전망).
	private final GeminiBatchApi batchApi;
	private final AccountBatchCollectJob collectJob;

	/** @param batchApi 배치 전송 제출용 — null이면 배치 미지원 프로바이더(온라인 폴백). */
	public AccountAnalysisJob(DataSource analysisDataSource, AccountSynthesisPort port,
			AnalyticsSettings settings, ProgressReporter reporter, TraitTaxonomyLoader traitLoader,
			GeminiBatchApi batchApi) {
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.port = port;
		this.settings = settings;
		this.reporter = reporter;
		this.traitLoader = traitLoader;
		this.batchApi = batchApi;
		this.collectJob = new AccountBatchCollectJob(analysisDataSource, batchApi, traitLoader, settings);
	}

	/** analyzeOne의 처리 결과 — 데이터 미비 스킵은 실패가 아니라 별도 분기로 집계한다. */
	private enum Outcome { PROCESSED, SKIPPED_DATA_INCOMPLETE }

	/** @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부) */
	public JobResult run() {
		List<String> targets = analysis.queryForList("""
				SELECT s.handle
				FROM account_summaries s
				""" + ELIGIBLE_WHERE + """

				ORDER BY s.handle
				LIMIT ?""", String.class,
				settings.accountAnalyzeCooldownDays(), CopyRules.VERSION, settings.accountAnalyzeBatchLimit());

		if (settings.accountBatchTransportEnabled()) {
			if (batchApi != null) {
				return submitBatch(targets);
			}
			log.warn("account-analyze-transport=batch이나 활성 프로바이더가 배치 미지원 — 온라인 경로로 폴백");
		}
		return runOnline(targets, settings.activeLlmModel());
	}

	private JobResult runOnline(List<String> targets, String model) {
		int processed = 0;
		int failed = 0;
		int skippedIncomplete = 0;
		boolean carriedOver = false;
		reporter.report(0, 0, targets.size());
		for (String handle : targets) {
			try {
				if (analyzeOne(handle, model) == Outcome.PROCESSED) {
					processed++;
				} else {
					skippedIncomplete++;
				}
			} catch (LlmQuotaExhaustedException e) {
				// 일 한도 소진 — 에러가 아닌 이월: 남은 대상은 다음 실행에서 자연 재대상 (07-18 확정)
				log.warn("LLM 일 한도 소진 — 배치 중단, 잔여 {}건 이월", targets.size() - processed - failed);
				carriedOver = true;
				break;
			} catch (Exception e) {
				failed++;
				log.error("account copy failed for {} — 다음 실행에서 재대상", handle, e);
			}
			reporter.report(processed, failed, targets.size());
		}
		if (skippedIncomplete > 0) {
			// 이 스킵은 "미러가 정상화될 때까지 매 배치에서 같은 계정이 후보로 다시 잡히는" 상태를
			// 만든다 — 과거 무한 재대상 루프 사고(is_beauty NULL 재분류, 07-21)와 표면은 닮았지만
			// 원인이 다르다: 그때는 재대상 조건 자체가 결정론적으로 절대 해소되지 않아 문제였고,
			// 여기는 뷰(analytics/views/10_account_detail.sql) 적용 한 번으로 다음 미러 실행부터
			// 9컬럼이 채워져 자연히 해소된다 — 코드 쪽에서 뭘 더 고쳐야 하는 상태가 아니다.
			// WARN으로 남기는 건 운영자가 "뷰 적용을 안 했다"는 걸 알아챌 유일한 신호이기 때문.
			// (dataIncomplete()는 always-strip 7컬럼만 검사한다 — median 2개는 정상 운영에서도
			// NULL일 수 있어 이 감지에서 빠졌다. PerfConfidence.CONFIDENCE_COLUMNS javadoc 참조.)
			log.warn("계정 {}건 스킵 — 신뢰도 판정 컬럼 7개가 전부 NULL이라 데이터 미비로 판단"
					+ "(뷰 미적용/미러 실패 의심). analytics/views/10_account_detail.sql 적용 후"
					+ " 다음 미러·배치에서 자연 재대상됨", skippedIncomplete);
		}
		log.info("account copy complete ({} accounts, {} failed, {} skipped)", processed, failed, skippedIncomplete);
		return new JobResult(processed, failed, carriedOver);
	}

	/** 배치 전송 제출 — 제출 전 pending 잔여를 먼저 수거해 중복 제출을 완화한다(콘텐츠 동형). */
	private JobResult submitBatch(List<String> targets) {
		JobResult swept = collectJob.run();
		if (swept.processed() > 0 || swept.failed() > 0) {
			log.info("계정 배치 제출 전 pending 수거 — {}건 저장, {}건 실패", swept.processed(), swept.failed());
		}
		if (targets.isEmpty()) {
			log.info("계정 배치 제출 대상 없음 — 제출 생략");
			return new JobResult(0, 0, false);
		}
		String model = settings.activeLlmModel();
		TraitTaxonomy vocab = traitLoader.get();
		StringBuilder jsonl = new StringBuilder();
		StringBuilder sidecar = new StringBuilder();
		int skippedIncomplete = 0;
		int submitted = 0;
		for (String handle : targets) {
			Prepared p = prepare(handle);
			if (p == null) {
				skippedIncomplete++; // 온라인 경로의 SKIPPED_DATA_INCOMPLETE와 동일 — 제출에서 제외
				continue;
			}
			String system = GeminiAccountSynthesizer.instructions(vocab, p.account().confidence());
			jsonl.append(json.writeValueAsString(AccountBatchLines.requestLine(json, handle, system,
					GeminiAccountSynthesizer.userText(p.account())))).append('\n');
			sidecar.append(json.writeValueAsString(AccountBatchLines.sidecarLine(json, handle,
					p.lastPostedAt(), p.analyzedCount(), p.adSituation()))).append('\n');
			submitted++;
		}
		if (skippedIncomplete > 0) {
			// 온라인 경로 run()의 skippedIncomplete WARN(위 runOnline)과 같은 취지 — 장문 설명은
			// 거기 원본 참조. 배치 제출에서는 사이드카·전송 준비 맥락만 짧게 남긴다.
			log.warn("계정 {}건 스킵 — 데이터 미비로 배치 제출에서 제외(뷰 미적용/미러 실패 의심)",
					skippedIncomplete);
		}
		if (submitted == 0) {
			log.info("계정 배치 제출 대상 전량 스킵 — 제출 생략");
			return new JobResult(0, 0, false);
		}
		String fileName = batchApi.uploadFile(
				jsonl.toString().getBytes(StandardCharsets.UTF_8), "hypenow-account");
		String batchName = batchApi.createBatch(model, fileName, "hypenow-account");
		// 사이드카는 DB 컬럼 보관 — 컨테이너에 쓰기 볼륨이 없어 로컬 파일은 배포 교체 시 유실 좀비(08-11 리뷰)
		analysis.update("""
				INSERT INTO account_batch_jobs (batch_name, submitted_count, status, sidecar_jsonl)
				VALUES (?, ?, 'pending', ?)""", batchName, submitted, sidecar.toString());
		log.info("계정 배치 제출 완료 — batch={}, {}건", batchName, submitted);
		return new JobResult(submitted, 0, false);
	}

	/** 제출·온라인 공용 준비물 — LLM 입력과, 저장 시점에 필요한 스냅샷(사이드카행). */
	record Prepared(AccountToAnalyze account, OffsetDateTime lastPostedAt, Long analyzedCount,
			AdSituation adSituation) {
	}

	/** @return null이면 데이터 미비 스킵(SKIPPED_DATA_INCOMPLETE — 배포 과도기 가드 주석 참조) */
	private Prepared prepare(String handle) {
		Map<String, Object> summary = analysis.queryForMap(
				"SELECT * FROM account_summaries WHERE handle = ?", handle);
		// last_posted_at(timestamptz)만 타입 지정 조회가 필요 — queryForMap은 Timestamp를 돌려줘 record 타입과 어긋남.
		// 나머지 스냅샷 값(bigint 등)은 summary 맵 캐스팅으로 충분하다.
		OffsetDateTime lastPostedAt = analysis.queryForObject(
				"SELECT last_posted_at FROM account_summaries WHERE handle = ?", OffsetDateTime.class, handle);
		Long analyzedCount = (Long) summary.get("analyzed_count");
		List<Map<String, Object>> categories = analysis.queryForList("""
				SELECT main_group, content_count FROM account_category_stats
				WHERE account_handle = ? ORDER BY content_count DESC, main_group ASC""", handle);
		List<Map<String, Object>> posts = AccountAdCanon.loadPosts(analysis, handle);
		// 광고 판정·수치는 캡션 분류(ad_type) 정본 — 미러의 ad_marked 집계는 릴스 전용이라 쓰지 않는다.
		AccountAdCanon.AdMetrics ad = AccountAdCanon.load(analysis, handle, (String) summary.get("metric"));
		AdSituation adSituation = ad.situation();

		// 판정(원본 9컬럼 포함)·프롬프트 사본(always-strip 7컬럼 + 조건부 제거, median 2개는 판정
		// 근거로 노출) 양쪽을 한 번에 만드는 공용 헬퍼 — ClaudeBurstRunner와 공유한다(한쪽만
		// 벗겨내면 가드가 조용히 우회되는 재발 방지).
		AccountAdCanon.SummaryWithConfidence sc = AccountAdCanon.withConfidence(summary, ad);

		// 배포 과도기 가드(설계 §7 배포 순서): 뷰 선적용 없이 마이그레이션이 먼저 배포되면 미러가
		// 9개 신 컬럼을 채우지 못한 채로 남는다 — 이 상태에서 카피를 만들면 모든 계정이 최대
		// 억제 등급을 받아 저품질 문구가 CopyRules.VERSION으로 찍히고, 버전 게이트 때문에 뷰를
		// 나중에 올려도 복구되지 않는다(영구 고정). 아무것도 안 쓰고 건너뛰는 편이 안전 — 기존
		// 문구가 그대로 서빙되고, 이 계정은 ELIGIBLE_WHERE의 "분석 이력 없음" 조건으로 다음
		// 실행에서도 계속 후보로 잡힌다(run()의 집계 로그 참조 — 의도된 동작).
		if (sc.confidence().dataIncomplete()) {
			return null;
		}

		List<Map<String, Object>> promptPosts = AccountAdCanon.withPostConfidence(posts, sc.confidence());
		AccountToAnalyze account = new AccountToAnalyze(handle,
				sc.promptSummary(), categories, promptPosts, adSituation, sc.confidence());
		return new Prepared(account, lastPostedAt, analyzedCount, adSituation);
	}

	private Outcome analyzeOne(String handle, String model) {
		Prepared p = prepare(handle);
		if (p == null) {
			return Outcome.SKIPPED_DATA_INCOMPLETE;
		}
		AccountCopy copy = port.synthesize(p.account());
		// 이력 INSERT 전 가드 — 빈 카피가 "최신 행"으로 서빙되는 것을 차단 (B3의 빈 종합 가드와 동일 취지).
		// 절단·INSERT는 AccountAnalysisWriter 단일 원천(ClaudeBurstRunner와 공유 — 07-17 재발 방지).
		if (!AccountAnalysisWriter.isValid(copy)) {
			throw new IllegalStateException("계정 카피가 비어 있음: " + handle);
		}
		AccountAnalysisWriter.insert(analysis, json, handle, OffsetDateTime.now(), model,
				p.lastPostedAt(), p.analyzedCount(), copy, p.adSituation(), traitLoader.get().names());
		return Outcome.PROCESSED;
	}
}
