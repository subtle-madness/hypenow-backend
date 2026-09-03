package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentSynthesisPort;
import com.celfit.analytics.llm.ContentToSynthesize;
import com.celfit.analytics.llm.Synthesis;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 해석 문구 갱신 배치 (07-21) — 낡은 문구만 다시 만들고 <b>사실 추출은 보존</b>한다.
 *
 * <p>왜 필요한가: content_analyses 한 행에 성격이 다른 둘이 섞여 있다. 사실(브랜드·카테고리·ad_type)은
 * 캡션·썸네일에만 의존해 안 낡지만, 해석 문구는 기준선 수치를 인용해서 기준선 정의가 바뀌면 낡는다
 * (07-21 하루에 참여율 분모·contentsPattern 의미가 둘 다 바뀜). 통합 재분석은 멀쩡한 사실까지 버리고
 * 썸네일·분류표를 다시 태우므로, 낡은 부분만 갱신한다.
 *
 * <p>대상: {@code synthesis_version}이 {@link Synthesis#VERSION}과 다르거나 NULL인 행.
 * <b>후보 자격(최근창·성숙 가드)과 무관</b>하다 — 사실은 이미 있고 계정 평균 기준선은 언제나
 * 계산되므로, 재분석 후보에서 빠진 옛 콘텐츠도 갱신할 수 있다(전량 재분석으로는 손댈 수 없던 영역).
 *
 * <p>rank_in_recent_reels는 최근창 안일 때만 채워진다 — 밖이면 null로, 통합 잡의 폴백과 같은 규칙.
 */
public class ContentSynthesisRefreshJob {

	private static final Logger log = LoggerFactory.getLogger(ContentSynthesisRefreshJob.class);
	/** 계정 집계마저 없는 이례적 케이스용 — 전부 null (프롬프트가 앵커 없이 절제 처리). */
	private static final Baseline EMPTY_BASELINE =
			new Baseline(null, null, null, null, null, null, null, null, null, null);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final ContentSynthesisPort port;
	private final AnalyticsSettings settings;
	private final ProgressReporter reporter;

	public ContentSynthesisRefreshJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentSynthesisPort port, AnalyticsSettings settings, ProgressReporter reporter) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.port = port;
		this.settings = settings;
		this.reporter = reporter;
	}

	/** @return 잡 실행 결과 (갱신·실패 건수) */
	public JobResult run() {
		List<String> targets = analysis.queryForList("""
				SELECT short_code FROM content_analyses
				WHERE synthesis_version IS DISTINCT FROM ?
				ORDER BY short_code
				LIMIT ?""", String.class, Synthesis.VERSION, settings.analyzeBatchLimit());
		if (targets.isEmpty()) {
			log.info("해석 문구 갱신 대상 없음 (계약 v{})", Synthesis.VERSION);
			return new JobResult(0, 0, false);
		}
		Map<String, Baseline> withBaseline = BaselineLoader.byShortCode(raw);
		Map<String, Baseline> accountBaseline = BaselineLoader.byAccount(raw);
		String model = settings.activeLlmModel();
		int processed = 0;
		int failed = 0;
		int skipped = 0;
		boolean carriedOver = false;
		reporter.report(0, 0, targets.size());
		for (String shortCode : targets) {
			try {
				if (refreshOne(shortCode, model, withBaseline, accountBaseline)) {
					processed++;
				} else {
					skipped++;
				}
			} catch (com.celfit.analytics.llm.LlmQuotaExhaustedException e) {
				// 일 한도 소진 — 에러가 아닌 이월 (통합 잡과 같은 규약)
				log.warn("LLM 일 한도 소진 — 갱신 중단, 잔여 {}건 이월", targets.size() - processed - failed);
				carriedOver = true;
				break;
			} catch (Exception e) {
				failed++;
				log.error("해석 문구 갱신 실패: {} — 다음 실행에서 재대상", shortCode, e);
			}
			reporter.report(processed, failed, targets.size());
		}
		log.info("해석 문구 갱신 완료 ({}건 갱신, {}건 실패, {}건 앵커 없어 보존)", processed, failed, skipped);
		return new JobResult(processed, failed, carriedOver);
	}

	/**
	 * @return 갱신했으면 true, 앵커를 못 구해 <b>보존</b>했으면 false.
	 *         앵커 없이 다시 만들면 "표본이 부족해 판단하기 어렵다"류로 <b>기존보다 나빠지므로</b>
	 *         손대지 않는다 — 미러에서 빠져 contents·시계열 어디에도 없는 옛 행이 여기 해당한다.
	 */
	private boolean refreshOne(String shortCode, String model,
			Map<String, Baseline> withBaseline, Map<String, Baseline> accountBaseline) {
		// 지표·계정은 미러 contents 우선, 없으면 계정 상세 시계열로 폴백 —
		// 미러가 서빙 자격 집합만 담아 옛 콘텐츠가 빠지기 때문(둘 다 없으면 아래에서 보존).
		Map<String, Object> row = analysis.queryForMap("""
				SELECT a.short_code, a.main_category, a.sub_categories, a.ad_type, a.ad_disclosure,
				       a.detected_brands, a.detected_products, a.detected_product_categories,
				       a.sponsored_signal_level, a.is_beauty, a.metric_timeliness,
				       COALESCE(c.account_handle, s.account_handle) AS account_handle,
				       COALESCE(c.content_type, s.content_type)     AS content_type,
				       COALESCE(c.views, s.views)                   AS views,
				       COALESCE(c.likes, s.likes)                   AS likes,
				       COALESCE(c.comments, s.comments)             AS comments
				FROM content_analyses a
				LEFT JOIN contents c ON c.short_code = a.short_code
				LEFT JOIN account_content_series s ON s.short_code = a.short_code
				WHERE a.short_code = ?""", shortCode);
		Baseline b = withBaseline.get(shortCode);
		if (b == null) {
			b = accountBaseline.get((String) row.get("account_handle"));
		}
		if (b == null || b.equals(EMPTY_BASELINE)) {
			log.debug("기준선 앵커 없음 — 기존 문구 보존: {}", shortCode);
			return false;
		}
		Map<String, Long> categoryCounts = new LinkedHashMap<>();
		analysis.query("""
				SELECT ai_category, count(*) AS cnt FROM comment_classifications
				WHERE short_code = ? GROUP BY ai_category""",
				rs -> {
					categoryCounts.put(rs.getString(1), rs.getLong(2));
				}, shortCode);

		Synthesis s = port.synthesize(new ContentToSynthesize(shortCode,
				(String) row.get("account_handle"), (String) row.get("content_type"),
				(Long) row.get("views"), (Long) row.get("likes"), (Long) row.get("comments"),
				PromptBaseline.of(b), categoryCounts, StoredFacts.of(row)));

		// 빈 종합은 저장하지 않는다 — 기존 문구가 낡았어도 빈 문구보다는 낫다(가드는 통합 잡과 동일 취지).
		if (s.aiContentSummary() == null || s.aiContentSummary().isBlank()) {
			throw new IllegalStateException("해석 문구가 비어 있음: " + shortCode);
		}
		// 지표 시점은 수집 시점 사실이라 재생성이 바꾸지 않는다 - 저장된 값을 그대로 되돌려 넣는다
		// (2026-09-03 updateSynthesis가 metric_timeliness를 SET하게 되면서 생긴 호출 계약).
		ContentAnalysisWriter.updateSynthesis(analysis, shortCode, model, b, s,
				(String) row.get("metric_timeliness"));
		return true;
	}

}
