package com.celfit.instagram.source;

/**
 * 코드별 관측 지표 — igPlays는 IG 전용, fbPlays는 null(키 부재)과 0(관측된 0)을 구분한다.
 * saves·shares·reposts도 함께 나른다(08-04): 저장·리포스트 키는 세션 복권(콜 단위 전부/전무,
 * clips 존재율 ~45%)이라 clips 관측을 버리면 medias(~30%)보다 좋은 공급원을 매일 흘리게 된다.
 */
public record ClipCounts(Long igPlays, Long fbPlays, Long saves, Long shares, Long reposts) {

	/** 저장·공유·리포스트 중 하나라도 실렸는가 — 세션 복권 당첨 판정(재시도 중단 기준). */
	public boolean hasMetricKeys() {
		return saves != null || shares != null || reposts != null;
	}
}
