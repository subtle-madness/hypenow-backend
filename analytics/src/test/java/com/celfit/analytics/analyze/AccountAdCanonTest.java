package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.celfit.contract.analysis.AccountSummary;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 프롬프트 입력에서 카피 무관 컬럼을 제거하는 계약 — email(V46, 스펙
 * 2026-07-30-influencer-email-from-bio) 재발 방지가 핵심이다. 계정 카피 생성은
 * {@code SELECT * FROM account_summaries} 결과를 통째로 프롬프트에 싣는데, 트랙 BB가 추가한
 * email 컬럼이 카피에 전혀 쓰이지 않으면서도 이 구조 때문에 자동으로 프롬프트에 실려 실
 * 연락처가 외부 LLM(Gemini)로 전송됐다(07-30 발견).
 */
class AccountAdCanonTest {

	private static AccountAdCanon.AdMetrics noAdMetrics() {
		return new AccountAdCanon.AdMetrics(0L, null, null, null, 0L, 0L, null);
	}

	@Test
	void email이_프롬프트_입력_맵에서_제거된다() {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("handle", "acct");
		summary.put("email", "person@example.com");

		AccountAdCanon.SummaryWithConfidence sc = AccountAdCanon.withConfidence(summary, noAdMetrics());

		assertFalse(sc.promptSummary().containsKey("email"),
				"email이 프롬프트 입력 맵에 남아 있음 — 외부 LLM으로 개인정보가 전송된다: " + sc.promptSummary());
	}

	/**
	 * 재발 방지 못박기 — account_summaries(AccountSummary record)의 필드를 리플렉션으로 훑어
	 * 프롬프트 입력을 만든 뒤, 프롬프트에 남는 키 집합이 여기 하드코딩한 목록과 정확히 같은지
	 * 검증한다. 앞으로 AccountSummary에 필드가 추가되면(email처럼 미러 컬럼이 늘면) 이 입력
	 * 맵에도 리플렉션으로 자동 반영되므로, 그 필드를 always-strip(CONFIDENCE_COLUMNS)·카피
	 * 무관(PROMPT_IRRELEVANT_COLUMNS)·조건부 제외(excludedSummaryKeys) 중 어디에도 넣지 않으면
	 * 이 테스트가 즉시 깨진다 — "프롬프트에 실려도 되는가"를 명시적으로 결정하도록 강제한다.
	 *
	 * <p>등급 판정이 전부 OK가 되도록 신뢰도 컬럼은 값을 채우고, median 두 컬럼만 NULL로 둬
	 * 조건부 제거(excludedSummaryKeys)가 아무것도 걸리지 않는 기준 상태를 만든다 — 조건부 제거
	 * 자체는 PerfConfidenceTest·AccountAnalysisJobTest가 이미 별도로 검증한다.
	 */
	@Test
	void 프롬프트에_실리는_키_집합이_기대_목록과_정확히_일치한다() {
		Map<String, Object> summary = fullAccountSummaryFixture();

		AccountAdCanon.SummaryWithConfidence sc = AccountAdCanon.withConfidence(summary, noAdMetrics());

		Set<String> expected = Set.of(
				"handle", "followers", "follows_count", "posts_count", "biography", "analyzed_count",
				"views_count", "metric", "avg_views", "views_per_follower", "avg_er_pct", "avg_likes",
				"avg_comments", "trend_direction", "trend_change_pct", "trend_older_avg", "trend_newer_avg",
				"sponsored_count", "organic_avg", "ad_avg", "ad_drop_pct", "comparison_organic_count",
				"comparison_ad_count", "last_ad_posted_at", "last_posted_at", "avg_interval_days",
				"avg_hype_score", "median_views", "median_er_pct");
		assertEquals(expected, sc.promptSummary().keySet(),
				"프롬프트 입력 키 집합이 기대 목록과 다르다 — account_summaries에 필드가 추가됐다면 "
						+ "always-strip(PerfConfidence.CONFIDENCE_COLUMNS)·카피 무관"
						+ "(AccountAdCanon.PROMPT_IRRELEVANT_COLUMNS) 중 어디로 제외할지 결정하고, "
						+ "프롬프트에 실려야 한다면 이 expected 목록에 추가하라: " + sc.promptSummary().keySet());
	}

	/** AccountSummary의 모든 필드를 리플렉션으로 훑어 타입별 대표값을 채운 스냅샷(SELECT * 흉내). */
	private static Map<String, Object> fullAccountSummaryFixture() {
		Map<String, Object> m = new LinkedHashMap<>();
		for (RecordComponent c : AccountSummary.class.getRecordComponents()) {
			m.put(toSnakeCase(c.getName()), dummyValueOf(c.getType()));
		}
		// 신뢰도 판정 7컬럼을 OK 등급이 나오는 값으로 덮어써 excludedSummaryKeys()가 비게 만든다.
		m.put("views_sample_count", 10);
		m.put("likes_sample_count", 10);
		m.put("comments_sample_count", 10);
		m.put("reels_count", 10);
		m.put("feed_count", 10);
		m.put("top_views_share_pct", 10);
		m.put("window_span_days", 10);
		// median 두 컬럼은 NULL로 둬 "median 존재 시 대응 avg 제거" 조건을 걸지 않는다.
		m.put("median_views", null);
		m.put("median_er_pct", null);
		return m;
	}

	private static Object dummyValueOf(Class<?> type) {
		if (type == String.class) {
			return "x";
		}
		if (type == Long.class || type == long.class) {
			return 10L;
		}
		if (type == Integer.class || type == int.class) {
			return 10;
		}
		if (type == BigDecimal.class) {
			return BigDecimal.ONE;
		}
		if (type == OffsetDateTime.class) {
			return OffsetDateTime.now();
		}
		throw new IllegalStateException("AccountSummary에 다루지 않는 타입이 추가됨: " + type
				+ " — 이 테스트의 dummyValueOf에 케이스를 추가하라");
	}

	// MirrorJob.toSnakeCase(package-private, 다른 패키지)와 동일 규약의 복제 — 테스트 전용이라
	// 프로덕션 코드 접근성을 넓히지 않는다.
	private static String toSnakeCase(String camel) {
		return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
	}
}
