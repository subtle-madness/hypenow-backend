package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 프롬프트 기준선 조립 계약 — 참여율은 <b>화면과 같은 단위·같은 반올림</b>으로 실린다.
 *
 * <p>저장·뷰는 비율(0.0812), 화면(was)은 퍼센트(8.12)라 단위 표기 없이 비율을 넣으면 LLM이
 * 단위를 추측한다(운영 실측 22건이 원값을 그대로 인용해 100배 어긋남). 키 이름에 pct를 박고
 * 값을 변환해 추측 여지를 없앤 것을 고정한다.
 */
class PromptBaselineTest {

	/** was V1ContentReportAssembler.engagementRateValue와 같은 산식 — 이 테스트가 두 구현을 묶는다. */
	private static BigDecimal wasDisplayFormula(long likes, long comments, long followers) {
		return BigDecimal.valueOf((likes + comments) * 100L)
				.divide(BigDecimal.valueOf(followers), 2, RoundingMode.HALF_UP);
	}

	@Test
	void 참여율은_화면과_같은_퍼센트_단위로_실린다() {
		// 뷰가 저장하는 비율: (520+52)/7045 = 0.081192...
		BigDecimal ratio = new BigDecimal("0.081192");

		BigDecimal prompt = PromptBaseline.toPercent(ratio);

		assertEquals(new BigDecimal("8.12"), prompt);
		assertEquals(wasDisplayFormula(520, 52, 7045), prompt, "화면 산식과 자릿수까지 일치해야 한다");
	}

	@Test
	void 키_이름에_단위가_박혀_있다() {
		Map<String, Object> m = PromptBaseline.of(new Baseline(
				9000L, 2, 3, 3, new BigDecimal("0.0812"), 940L, 61L, 67, 19333L, 3L));

		assertTrue(m.containsKey("recent12_avg_engagement_rate_pct"), m.toString());
		// 단위 없는 옛 키가 남아 있으면 LLM이 다시 추측하게 된다
		assertTrue(!m.containsKey("recent12_avg_engagement_rate"), m.toString());
		assertEquals(new BigDecimal("8.12"), m.get("recent12_avg_engagement_rate_pct"));
	}

	@Test
	void 참여율이_없으면_null로_둔다() {
		Map<String, Object> m = PromptBaseline.of(
				new Baseline(null, null, null, null, null, null, null, null, null, null));

		assertNull(m.get("recent12_avg_engagement_rate_pct"));
	}

	/** 러너는 record가 아니라 raw 뷰 행을 그대로 다룬다 — 같은 변환이 적용돼야 한다. */
	@Test
	void raw_뷰_행_경로도_같은_변환을_쓴다() {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("recent_reels_avg_views", 9000L);
		row.put("rank_in_recent_reels", 2);
		row.put("recent12_avg_engagement_rate", new BigDecimal("0.0049"));

		Map<String, Object> m = PromptBaseline.ofRow(row);

		assertEquals(new BigDecimal("0.49"), m.get("recent12_avg_engagement_rate_pct"));
		assertEquals(9000L, m.get("recent_reels_avg_views"));
	}
}
