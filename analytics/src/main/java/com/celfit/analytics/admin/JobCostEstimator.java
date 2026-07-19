package com.celfit.analytics.admin;

import com.celfit.analytics.config.AnalyticsSettings;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * LLM 잡 예상 비용 카드 — 실행 전 "대상 몇 건, 대략 얼마"를 보여준다 (crawler 잡 비용 카드 UX).
 * 대상 선정 쿼리는 각 잡의 로직 복제 — 잡과 어긋나면 카드가 거짓말을 하므로 잡 수정 시 함께 고칠 것.
 * 단가는 ARCHITECTURE §6 실측값 기반 추정치.
 */
public class JobCostEstimator {

	public record CostCard(JobName job, String label, int targets,
			BigDecimal minUsd, BigDecimal maxUsd, String note) {

		public static CostCard of(JobName job, int targets, String unitMin, String unitMax, String note) {
			BigDecimal count = BigDecimal.valueOf(targets);
			return new CostCard(job, job.label(), targets,
					unitMin == null ? null : new BigDecimal(unitMin).multiply(count),
					unitMax == null ? null : new BigDecimal(unitMax).multiply(count),
					note);
		}
	}

	// §6 실측: 댓글 분류 1,000건당 haiku $12.2 / opus $61 → 게시물당
	static final String CLASSIFY_UNIT_MIN = "0.0122";
	static final String CLASSIFY_UNIT_MAX = "0.061";
	// §6 실측: VLM 건당 $0.03~0.05 (캡션 종합 포함 추정)
	static final String ANALYZE_UNIT_MIN = "0.03";
	static final String ANALYZE_UNIT_MAX = "0.05";

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final AnalyticsSettings settings;

	public JobCostEstimator(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			AnalyticsSettings settings) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.settings = settings;
	}

	public List<CostCard> costCards() {
		return List.of(
				CostCard.of(JobName.CLASSIFY, classifyTargets(), CLASSIFY_UNIT_MIN, CLASSIFY_UNIT_MAX,
						"게시물당 $0.0122(haiku)~$0.061(opus) — 07-12 실측. 댓글 수집 MVP 제외로 대개 0건"),
				CostCard.of(JobName.ANALYZE, analyzeTargets(), ANALYZE_UNIT_MIN, ANALYZE_UNIT_MAX,
						"기본 Gemini 무료 티어 $0 — 표시 단가는 anthropic 롤백 시 참고치($0.03~0.05, 07-14 실측)"),
				CostCard.of(JobName.ACCOUNT_ANALYZE, accountAnalyzeTargets(), null, null,
						"기본 Gemini 무료 티어 $0 — 건수만 표시 (계정당 1콜)"));
	}

	/** CommentClassificationJob.run()의 대상 선정 복제. */
	int classifyTargets() {
		Set<String> classified = new HashSet<>(analysis.queryForList(
				"SELECT DISTINCT short_code FROM comment_classifications", String.class));
		long count = raw.queryForList(
				"SELECT DISTINCT short_code FROM analytics.v_content_comments", String.class)
				.stream().filter(sc -> !classified.contains(sc)).count();
		return (int) Math.min(count, settings.analyzeBatchLimit());
	}

	/** ContentAnalysisJob.run()의 대상 선정 복제 (양쪽 DB 교집합 + 숙성 가드). */
	int analyzeTargets() {
		Set<String> withBaseline = new HashSet<>(raw.queryForList(
				"SELECT short_code FROM analytics.v_analysis_baseline", String.class));
		long count = analysis.queryForList("""
				SELECT c.short_code FROM contents c
				WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
				  AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
				       OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
				  AND c.posted_at <= now() - make_interval(days => ?)""",
				String.class, settings.analyzeMaturityDays())
				.stream().filter(withBaseline::contains).count();
		return (int) Math.min(count, settings.analyzeBatchLimit());
	}

	/** AccountAnalysisJob.run()의 대상 선정 쿼리를 count로 감쌈. */
	int accountAnalyzeTargets() {
		Integer count = analysis.queryForObject("""
				SELECT count(*) FROM (
				  SELECT s.handle
				  FROM account_summaries s
				  LEFT JOIN LATERAL (
				    SELECT a.input_last_posted_at, a.analyzed_at
				    FROM account_analyses a WHERE a.handle = s.handle
				    ORDER BY a.analyzed_at DESC LIMIT 1
				  ) latest ON true
				  WHERE latest.analyzed_at IS NULL
				     OR (latest.input_last_posted_at IS DISTINCT FROM s.last_posted_at
				         AND latest.analyzed_at < now() - make_interval(days => ?))
				  LIMIT ?
				) t""", Integer.class,
				settings.accountAnalyzeCooldownDays(), settings.accountAnalyzeBatchLimit());
		return count == null ? 0 : count;
	}
}
