package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.BeautyTaxonomy;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.llm.GeminiContentAnalyzer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 초기 백필 one-shot — 배치 API 일회 실행. 07-20 개정: provider=vertex면 Vertex 배치(GCS,
 * 잡 이름은 projects/{p}/locations/{loc}/batchPredictionJobs/{id} 전체 경로), 아니면
 * AI Studio 유료 키(GEMINI_API_KEY_PAID) Batch(배치 이름 batches/NNN) — 배선은 JobConfig.
 * submit: v_analysis_candidates 미분석 전량(계정 평균 앵커·rank는 최근창 안일 때만 — 07-20 스코프 확장,
 *         #79 재통합) → JSONL 업로드 → 배치 생성 →
 *         사이드카(프롬프트에 실은 기준선 스냅샷) 저장. 캡션 단독(썸네일 미첨부 — 서명 URL 대부분 만료).
 * collect: 상태 확인 → 결과 다운로드 → 파싱·sanitize → ON CONFLICT DO NOTHING INSERT(재실행 멱등).
 * 실행: --spring.main.web-application-type=none --analytics.backfill-submit=true
 *   → 로그의 배치 잡 이름 그대로 --analytics.backfill-collect=<잡 이름>
 */
public class GeminiBackfillRunner {

	private static final Logger log = LoggerFactory.getLogger(GeminiBackfillRunner.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final GeminiBatchApi api;
	private final AnalyticsSettings settings;
	private final BeautyTaxonomyLoader taxonomyLoader;
	private final Path workDir;
	private final ObjectMapper om = new ObjectMapper();

	public GeminiBackfillRunner(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			GeminiBatchApi api, AnalyticsSettings settings, BeautyTaxonomyLoader taxonomyLoader,
			Path workDir) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.api = api;
		this.settings = settings;
		this.taxonomyLoader = taxonomyLoader;
		this.workDir = workDir;
	}

	/** @return 배치 잡 이름 (collect 실행 시 그대로 넘긴다). 대상 없으면 null. */
	public String submit() {
		Set<String> analyzed = new HashSet<>(analysis.queryForList(
				"SELECT short_code FROM content_analyses", String.class));
		// 미분석 후보 전량 — 일상 잡·버스트와 동일 정의 (07-20 스코프 확장). 계정 평균(recent12_avg_*·
		// recent_reels_avg_views 등)은 account_handle 키로 항상 붙이고, rank는 최근창 안일 때만(short_code
		// 키 — 밖이면 null). 다작 계정의 최근창 밖 성숙분도 계정 평균을 앵커로 분석 대상에 포함.
		List<Map<String, Object>> rows = raw.queryForList("""
				SELECT c.short_code, c.account_handle, c.content_type, c.caption,
				       c.views, c.likes, c.comments, c.timely, c.ad_marked,
				       ab.recent_reels_avg_views, b.rank_in_recent_reels, ab.recent_reels_count,
				       ab.recent_contents_count, ab.recent12_avg_engagement_rate,
				       ab.recent12_avg_like_count, ab.recent12_avg_comment_count,
				       ab.category_top_percentile, ab.category_avg_views, ab.category_sample_size
				FROM v_analysis_candidates c
				LEFT JOIN v_analysis_account_baseline ab ON ab.account_handle = c.account_handle
				LEFT JOIN v_analysis_baseline b USING (short_code)
				ORDER BY c.short_code""");
		String system = GeminiContentAnalyzer.instructions(taxonomyLoader.get());
		StringBuilder jsonl = new StringBuilder();
		StringBuilder sidecar = new StringBuilder();
		int count = 0;
		for (Map<String, Object> r : rows) {
			String shortCode = (String) r.get("short_code");
			if (analyzed.contains(shortCode)) {
				continue;
			}
			// 백필은 댓글 분류 분포를 조회하지 않는다(기존 계약 유지 — 일회성 초기 백필 시점엔
			// comment_classifications가 아직 채워지지 않은 콘텐츠가 대부분이라 애초에 실효가 낮았다).
			// 상시 배치 제출 경로(ContentAnalysisJob)는 후보 게이트가 댓글 분류 완료를 보장하므로
			// 실분포를 싣는다 — 이 메서드는 명시적으로 빈 분포를 넘긴다(2026-08-11 리뷰 반영).
			jsonl.append(om.writeValueAsString(
					GeminiBatchLines.requestLine(om, shortCode, r, Map.of(), system))).append('\n');
			sidecar.append(om.writeValueAsString(GeminiBatchLines.sidecarLine(om, shortCode, r))).append('\n');
			count++;
		}
		if (count == 0) {
			log.info("백필 대상 없음");
			return null;
		}
		try {
			Files.createDirectories(workDir);
			Files.writeString(workDir.resolve("backfill-input.jsonl"), jsonl.toString());
			Files.writeString(workDir.resolve("backfill-sidecar.jsonl"), sidecar.toString());
		} catch (java.io.IOException e) {
			throw new IllegalStateException("백필 작업 파일 저장 실패: " + workDir, e);
		}
		String fileName = api.uploadFile(jsonl.toString().getBytes(StandardCharsets.UTF_8),
				"hypenow-backfill");
		String batchName = api.createBatch(settings.geminiModel(), fileName, "hypenow-backfill");
		log.info("백필 배치 제출 완료 — {}건, 배치: {} (수거: --analytics.backfill-collect={})",
				count, batchName, batchName);
		return batchName;
	}

	/** @return 저장 건수. 배치 미완료면 -1 (상태 로그만 — 나중에 다시 실행). */
	public int collect(String batchName) {
		JsonNode batch = om.readTree(api.getBatch(batchName));
		String state = GeminiBatchLines.state(batch);
		if (state == null || !state.endsWith("_SUCCEEDED")) {
			log.info("배치 미완료 — state={} (응답: {})", state, GeminiBatchLines.abbreviate(batch.toString()));
			return -1;
		}
		String resultFile = GeminiBatchLines.resultFileOf(batch);
		Map<String, Map<String, String>> sidecar =
				GeminiBatchLines.readSidecar(om, workDir.resolve("backfill-sidecar.jsonl"));
		String model = settings.geminiModel();
		BeautyTaxonomy taxonomy = taxonomyLoader.get();
		// 결과(운영 실측 119MB+)는 스트리밍으로 한 줄씩 받아 즉시 파싱·INSERT — 전체 적재 금지(07-20 OOM)
		java.util.concurrent.atomic.AtomicInteger saved = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
		api.downloadResults(resultFile, line -> {
			if (GeminiBatchLines.processResultLine(analysis, om, line, sidecar, model, taxonomy)) {
				saved.incrementAndGet();
			} else {
				failed.incrementAndGet();
			}
		});
		log.info("백필 저장 완료 — {}건 저장, {}건 실패(잔여는 일상 파이프라인이 흡수)", saved.get(), failed.get());
		return saved.get();
	}
}
