package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AccountCopy;
import com.celfit.analytics.llm.AccountSynthesisPort;
import com.celfit.analytics.llm.AccountToAnalyze;
import com.celfit.contract.analysis.AccountAnalysis;
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
 * 대상: ① 분석 없음 → 즉시 ② input_last_posted_at ≠ 미러 last_posted_at(새 게시물, stale)
 *       AND 마지막 분석 후 쿨다운(일) 경과. 계정 단위 실패 격리.
 */
public class AccountAnalysisJob {

	private static final Logger log = LoggerFactory.getLogger(AccountAnalysisJob.class);
	static final int MAX_TRAITS = 5; // ClaudeBurstRunner(구독 버스트 collect)와 공유
	static final int CAPTION_CHARS = 300;

	private final JdbcTemplate analysis;
	private final AccountSynthesisPort port;
	private final AnalyticsSettings settings;
	private final ProgressReporter reporter;
	private final ObjectMapper json = new ObjectMapper();

	public AccountAnalysisJob(DataSource analysisDataSource, AccountSynthesisPort port,
			AnalyticsSettings settings, ProgressReporter reporter) {
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.port = port;
		this.settings = settings;
		this.reporter = reporter;
	}

	/** @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부) */
	public JobResult run() {
		List<String> targets = analysis.queryForList("""
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
				ORDER BY s.handle
				LIMIT ?""", String.class,
				settings.accountAnalyzeCooldownDays(), settings.accountAnalyzeBatchLimit());
		String model = settings.activeLlmModel();
		int processed = 0;
		int failed = 0;
		boolean carriedOver = false;
		reporter.report(0, 0, targets.size());
		for (String handle : targets) {
			try {
				analyzeOne(handle, model);
				processed++;
			} catch (com.celfit.analytics.llm.LlmQuotaExhaustedException e) {
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
		log.info("account copy complete ({} accounts, {} failed)", processed, failed);
		return new JobResult(processed, failed, carriedOver);
	}

	private void analyzeOne(String handle, String model) {
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
		List<Map<String, Object>> posts = analysis.queryForList("""
				SELECT p.posted_at, p.content_type, p.views, p.likes, p.comments, p.sponsored,
				       left(c.caption, %d) AS caption
				FROM account_content_series p
				LEFT JOIN contents c ON c.short_code = p.short_code
				WHERE p.account_handle = ?
				ORDER BY p.posted_at ASC, p.short_code ASC""".formatted(CAPTION_CHARS), handle);
		boolean hasAdComparison = summary.get("organic_avg") != null && summary.get("ad_avg") != null;

		AccountCopy copy = port.synthesize(
				new AccountToAnalyze(handle, summary, categories, posts, hasAdComparison));

		// 이력 INSERT 전 가드 — 빈 카피가 "최신 행"으로 서빙되는 것을 차단 (B3의 빈 종합 가드와 동일 취지)
		if (isBlank(copy.tagline()) || isBlank(copy.summary())) {
			throw new IllegalStateException("계정 카피가 비어 있음: " + handle);
		}
		if (copy.traits() == null || copy.traits().isEmpty()) {
			throw new IllegalStateException("traits가 비어 있음: " + handle);
		}
		List<String> traits = List.copyOf(copy.traits().size() > MAX_TRAITS
				? copy.traits().subList(0, MAX_TRAITS) : copy.traits());

		AccountAnalysis row = new AccountAnalysis(handle, OffsetDateTime.now(), model,
				lastPostedAt, analyzedCount, copy.tagline(), copy.summary(), copy.trendNote(),
				copy.chartNote(), traits,
				hasAdComparison ? blankToNull(copy.adHeadline()) : null, copy.paceNote());
		analysis.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  input_analyzed_count, tagline, summary, trend_note, chart_note, traits,
				  ad_headline, pace_note)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
				row.handle(), row.analyzedAt(), row.model(), row.inputLastPostedAt(),
				row.inputAnalyzedCount(), row.tagline(), row.summary(), row.trendNote(),
				row.chartNote(), json.writeValueAsString(row.traits()), row.adHeadline(),
				row.paceNote());
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static String blankToNull(String s) {
		return isBlank(s) ? null : s;
	}
}
