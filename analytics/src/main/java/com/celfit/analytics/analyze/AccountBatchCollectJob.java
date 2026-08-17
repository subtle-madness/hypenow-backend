package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.GeminiBatchApi;
import com.celfit.analytics.llm.TraitTaxonomyLoader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 계정 카피 배치 수거 잡(2026-08-17) — 콘텐츠의 {@link ContentBatchCollectJob} 동형. account_batch_jobs의
 * pending 행을 순회해 배치 상태를 확인하고, 완료분은 결과를 파싱·저장한 뒤 상태를 전이시킨다.
 * 파싱·저장 로직은 {@link AccountBatchLines#processResultLine}이 맡는다. 사이드카(제출 시 프롬프트에
 * 실은 저장 시점 복원용 스냅샷)는 account_batch_jobs.sidecar_jsonl 컬럼에서 복원한다(로컬 파일이
 * 아니다 — analytics 컨테이너에는 쓰기 가능한 볼륨이 없어 제출~수거 사이 배포·컨테이너 교체가 끼면
 * 파일이 유실돼 pending 좀비가 남는다 — 콘텐츠 쪽과 동일한 이유).
 * 멱등: pending 행만 대상이라 이미 collected/failed로 전이된 배치는 다시 건드리지 않는다.
 */
public class AccountBatchCollectJob {

	private static final Logger log = LoggerFactory.getLogger(AccountBatchCollectJob.class);

	private final JdbcTemplate analysis;
	private final GeminiBatchApi batchApi; // null이면 배치 미지원 프로바이더 — run()이 no-op
	private final TraitTaxonomyLoader traitLoader;
	private final AnalyticsSettings settings;
	private final ObjectMapper om = new ObjectMapper();

	public AccountBatchCollectJob(DataSource analysisDataSource, GeminiBatchApi batchApi,
			TraitTaxonomyLoader traitLoader, AnalyticsSettings settings) {
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.batchApi = batchApi;
		this.traitLoader = traitLoader;
		this.settings = settings;
	}

	/** @return 수거 결과 — processed=이번 실행에서 새로 저장한 건수, failed=결과 판독 실패 건수.
	 *  아직 실행 중인 배치는 조용히 건너뛴다(다음 수거 사이클에서 재확인 — no-op). */
	public JobResult run() {
		if (batchApi == null) {
			return new JobResult(0, 0, false);
		}
		List<Map<String, Object>> pending = analysis.queryForList("""
				SELECT id, batch_name, sidecar_jsonl FROM account_batch_jobs
				WHERE status = 'pending' ORDER BY submitted_at""");
		int collected = 0;
		int failed = 0;
		for (Map<String, Object> row : pending) {
			long id = ((Number) row.get("id")).longValue();
			String batchName = (String) row.get("batch_name");
			String sidecarJsonl = (String) row.get("sidecar_jsonl");
			try {
				collected += collectOne(id, batchName, sidecarJsonl);
			} catch (Exception e) {
				failed++;
				log.error("계정 배치 수거 실패 — batch_name={}", batchName, e);
			}
		}
		if (collected > 0 || failed > 0) {
			log.info("계정 배치 수거 완료 — {}건 저장, {}건 실패", collected, failed);
		}
		return new JobResult(collected, failed, false);
	}

	/** @return 이번 배치에서 저장한 행 수. 실행 중이거나 상태 판정 불가면 0(행은 pending 유지). */
	private int collectOne(long id, String batchName, String sidecarJsonl) {
		JsonNode batch = om.readTree(batchApi.getBatch(batchName));
		String state = GeminiBatchLines.state(batch);
		if (state == null || state.endsWith("_RUNNING") || state.endsWith("_PENDING")
				|| state.endsWith("_QUEUED") || "JOB_STATE_UNSPECIFIED".equals(state)) {
			log.info("계정 배치 실행 중 — batch_name={}, state={}", batchName, state);
			return 0;
		}
		if (!state.endsWith("_SUCCEEDED")) {
			// 실패는 상태 전이만 하고 재시도하지 않는다 — 해당 계정들은 다음 실행의 stale/신규
			// 대상 판정에 여전히 안 걸려 자연히 재대상되므로 별도 재시도 로직이 불필요하다.
			markFailed(id, "배치 실패 상태: " + state);
			log.warn("계정 배치 실패 — batch_name={}, state={} (다음 실행에서 자동 재대상)", batchName, state);
			return 0;
		}
		String resultFile;
		try {
			resultFile = GeminiBatchLines.resultFileOf(batch);
		} catch (IllegalStateException e) {
			markFailed(id, "결과 파일 이름을 찾지 못함");
			log.warn("계정 배치 결과 파일 이름 판독 불가 — batch_name={}, 응답: {}", batchName, batch);
			return 0;
		}
		// 사이드카 유실/파싱 불가 — 좀비 pending 방지: 재시도하지 않고 failed로 접어 다음 실행이
		// 자연 재대상하게 한다(콘텐츠 쪽과 동일 취지 — 08-11 리뷰 지적).
		if (sidecarJsonl == null || sidecarJsonl.isBlank()) {
			markFailed(id, "사이드카 유실");
			log.warn("계정 사이드카 없음 — batch_name={} (다음 실행에서 자동 재대상)", batchName);
			return 0;
		}
		Map<String, Map<String, String>> sidecar;
		try {
			sidecar = AccountBatchLines.parseSidecar(om, sidecarJsonl);
		} catch (Exception e) {
			markFailed(id, "사이드카 파싱 실패");
			log.warn("계정 사이드카 파싱 실패 — batch_name={}", batchName, e);
			return 0;
		}
		String model = settings.activeLlmModel();
		var vocabulary = traitLoader.get().names();
		// 결과는 스트리밍으로 한 줄씩 받아 즉시 파싱·INSERT — 전체 적재 금지(콘텐츠 쪽 07-20 OOM 교훈 승계)
		AtomicInteger saved = new AtomicInteger();
		AtomicInteger lineFailed = new AtomicInteger();
		batchApi.downloadResults(resultFile, line -> {
			if (AccountBatchLines.processResultLine(analysis, om, line, sidecar, model, vocabulary)) {
				saved.incrementAndGet();
			} else {
				lineFailed.incrementAndGet();
			}
		});
		// 전체 스트리밍 성공 후에만 status를 전이한다(부분 처리 중 예외로 전이가 레이스되는 것을 방지 —
		// 콘텐츠 동형 순서). 수거 완료 — sidecar_jsonl은 더 이상 필요 없으니 NULL로 비워 테이블 비대를 막는다.
		analysis.update("""
				UPDATE account_batch_jobs SET status = 'collected', collected_at = now(), sidecar_jsonl = NULL
				WHERE id = ?""", id);
		log.info("계정 배치 수거 완료 — batch_name={}, {}건 저장, {}건 실패", batchName, saved.get(), lineFailed.get());
		return saved.get();
	}

	private void markFailed(long id, String note) {
		analysis.update("""
				UPDATE account_batch_jobs SET status = 'failed', collected_at = now(), note = ?, sidecar_jsonl = NULL
				WHERE id = ?""", note, id);
	}
}
