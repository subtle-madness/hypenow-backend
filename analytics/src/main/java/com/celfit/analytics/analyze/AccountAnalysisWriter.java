package com.celfit.analytics.analyze;

import com.celfit.analytics.llm.AccountCopy;
import com.celfit.analytics.llm.AdSituation;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * account_analyses 쓰기 단일 원천 — 일상 잡(AccountAnalysisJob)·구독 버스트 러너(ClaudeBurstRunner)가
 * 공유한다. 컬럼 변경 시 이 한 곳만 (ContentAnalysisWriter와 동일 취지 — 07-17 "한쪽만 고쳐지는 재발" 방지).
 */
final class AccountAnalysisWriter {

	/** 5개 초과 traits는 앞 5개만 저장 — AccountAnalysisJob·ClaudeBurstRunner 공유. */
	static final int MAX_TRAITS = 5;

	private AccountAnalysisWriter() {}

	/**
	 * 이력 INSERT 전 가드 — 빈 카피가 "최신 행"으로 서빙되는 것을 차단(B3의 빈 종합 가드와 동일 취지).
	 * 실패 시 호출자가 알아서 처리한다(Job은 throw, Runner는 skip).
	 */
	static boolean isValid(AccountCopy copy) {
		if (isBlank(copy.tagline()) || isBlank(copy.perfSummary()) || isBlank(copy.contentSummary())) {
			return false;
		}
		return copy.traits() != null && !copy.traits().isEmpty();
	}

	/**
	 * account_analyses 이력 INSERT. 호출 전 {@link #isValid}로 가드된 copy를 넘겨야 한다.
	 * adSituation이 {@link AdSituation#writesHeadline()}가 아니면(근거 없음) adSummary는 NULL로 버려진다.
	 * 구 카피 5컬럼(summary·trend/chart_note·ad_headline·pace_note)은 07-27 개편 후 미기록.
	 */
	static void insert(JdbcTemplate analysis, ObjectMapper json, String handle, OffsetDateTime analyzedAt,
			String model, OffsetDateTime inputLastPostedAt, Long inputAnalyzedCount,
			AccountCopy copy, AdSituation adSituation) {
		List<String> traits = List.copyOf(copy.traits().size() > MAX_TRAITS
				? copy.traits().subList(0, MAX_TRAITS) : copy.traits());
		String adSummary = adSituation != null && adSituation.writesHeadline()
				? blankToNull(copy.adSummary()) : null;
		analysis.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  input_analyzed_count, tagline, traits, perf_summary, content_summary, ad_summary)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""",
				handle, analyzedAt, model, inputLastPostedAt, inputAnalyzedCount,
				copy.tagline(), json.writeValueAsString(traits),
				copy.perfSummary(), copy.contentSummary(), adSummary);
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static String blankToNull(String s) {
		return isBlank(s) ? null : s;
	}
}
