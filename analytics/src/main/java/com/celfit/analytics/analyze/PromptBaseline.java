package com.celfit.analytics.analyze;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 프롬프트에 싣는 계정 기준선 맵 — 통합 분석 잡·해석 문구 갱신 잡·백필/버스트 러너가 공유한다.
 *
 * <p>네 곳에 같은 맵 조립이 복제돼 있었다. 07-21에 참여율 정의가 바뀌었을 때 뷰만 고쳐지고
 * 프롬프트 단위는 그대로 남은 것도 같은 이유라, 조립을 한 곳으로 모아 재발을 막는다.
 *
 * <p><b>참여율 단위</b>: 저장·뷰는 비율(0.0812)인데 화면(was
 * {@code V1ContentReportAssembler.engagementRateValue})은 퍼센트(8.12)로 보여준다.
 * 프롬프트에 비율을 단위 표기 없이 넣으면 LLM이 단위를 <b>추측</b>한다 — 운영 실측으로
 * 137건은 퍼센트로 변환해 맞혔지만 22건은 원값을 그대로 인용해
 * "평균 엔게이지먼트율 0.0023"(화면 0.23%)처럼 어긋났다.
 * 그래서 프롬프트에는 <b>화면과 같은 단위·같은 반올림</b>으로 넣고 키 이름에 단위를 박는다.
 * DB 컬럼(content_analyses.recent12_avg_engagement_rate)은 was 소비처가 많아 비율 그대로 둔다.
 */
final class PromptBaseline {

	/** 프롬프트 키 — 이름에 단위(pct)를 박아 LLM이 추측할 여지를 없앤다. */
	static final String ENGAGEMENT_RATE_KEY = "recent12_avg_engagement_rate_pct";

	private PromptBaseline() {
	}

	/** 로드된 기준선 record 기준 (통합 분석 잡·해석 문구 갱신 잡). */
	static Map<String, Object> of(Baseline b) {
		return build(b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentContentsCount(),
				b.recent12AvgEngagementRate(), b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
				b.categoryTopPercentile());
	}

	/** raw 뷰 행 기준 (백필·버스트 러너 — record로 만들지 않고 맵째 다룬다). */
	static Map<String, Object> ofRow(Map<String, Object> r) {
		return build(r.get("recent_reels_avg_views"), r.get("rank_in_recent_reels"),
				r.get("recent_contents_count"), r.get("recent12_avg_engagement_rate"),
				r.get("recent12_avg_like_count"), r.get("recent12_avg_comment_count"),
				r.get("category_top_percentile"));
	}

	private static Map<String, Object> build(Object avgViews, Object rank, Object contentsCount,
			Object engagementRatio, Object avgLikes, Object avgComments, Object categoryPercentile) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("recent_reels_avg_views", avgViews);
		m.put("rank_in_recent_reels", rank);
		m.put("recent_contents_count", contentsCount);
		m.put(ENGAGEMENT_RATE_KEY, toPercent(engagementRatio));
		m.put("recent12_avg_like_count", avgLikes);
		m.put("recent12_avg_comment_count", avgComments);
		m.put("category_top_percentile", categoryPercentile);
		return m;
	}

	/**
	 * 비율 → 퍼센트. 곱하기 100 후 소수 2자리 HALF_UP — was의 표시 산식과 <b>같은 규약</b>이라
	 * 프롬프트 수치와 화면 수치가 자릿수까지 일치한다.
	 */
	static BigDecimal toPercent(Object ratio) {
		if (ratio == null) {
			return null;
		}
		BigDecimal v = ratio instanceof BigDecimal bd ? bd : new BigDecimal(ratio.toString());
		return v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
	}
}
