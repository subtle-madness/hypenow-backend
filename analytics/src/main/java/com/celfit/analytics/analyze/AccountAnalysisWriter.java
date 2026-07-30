package com.celfit.analytics.analyze;

import com.celfit.analytics.llm.AccountCopy;
import com.celfit.analytics.llm.AdSituation;
import com.celfit.analytics.llm.CopyRules;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
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

	/** 어휘 통제(2026-07-29 스펙 §3-2): 어휘 밖 드롭 → 중복 제거(입력 순서 유지) → MAX_TRAITS 절단. */
	static List<String> sanitize(List<String> raw, Set<String> vocabulary) {
		return raw.stream().filter(vocabulary::contains).distinct().limit(MAX_TRAITS).toList();
	}

	/**
	 * account_analyses 이력 INSERT. 호출 전 {@link #isValid}로 가드된 copy를 넘겨야 한다.
	 * traits는 {@link #sanitize}를 거친다 — sanitize 후 0개면 빈 배열로 저장(어휘 통제 스펙:
	 * 믹스 성분으로 유사도 후보는 유지). adSituation이 {@link AdSituation#writesHeadline()}가
	 * 아니면(근거 없음) adSummary는 NULL로 버려진다.
	 * 구 카피 5컬럼(summary·trend/chart_note·ad_headline·pace_note)은 07-27 개편 후 미기록.
	 *
	 * <p>copy_version은 항상 현재 {@link CopyRules#VERSION}으로 찍는다(호출자와 무관 — 일상 잡·
	 * 버스트 러너 어느 경로로 생성됐든 "지금 규칙으로 만든 카피"라는 사실은 같다). 판정 규칙이
	 * 바뀌어 VERSION이 오르면 이 값보다 낮은 기존 행만 ELIGIBLE_WHERE가 재대상으로 잡는다(설계 §4).
	 */
	static void insert(JdbcTemplate analysis, ObjectMapper json, String handle, OffsetDateTime analyzedAt,
			String model, OffsetDateTime inputLastPostedAt, Long inputAnalyzedCount,
			AccountCopy copy, AdSituation adSituation, Set<String> vocabulary) {
		List<String> traits = sanitize(copy.traits(), vocabulary);
		String adSummary = adSituation != null && adSituation.writesHeadline()
				? blankToNull(copy.adSummary()) : null;
		analysis.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, input_last_posted_at,
				  input_analyzed_count, tagline, traits, perf_summary, content_summary, ad_summary,
				  copy_version)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""",
				handle, analyzedAt, model, inputLastPostedAt, inputAnalyzedCount,
				copy.tagline(), json.writeValueAsString(traits),
				copy.perfSummary(), copy.contentSummary(), adSummary, CopyRules.VERSION);
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static String blankToNull(String s) {
		return isBlank(s) ? null : s;
	}
}
