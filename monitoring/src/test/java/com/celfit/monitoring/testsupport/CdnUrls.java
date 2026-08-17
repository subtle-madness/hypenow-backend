package com.celfit.monitoring.testsupport;

import java.time.Instant;

/** 인스타 CDN 서명 URL 흉내 — 만료(oe) 판정·정렬 테스트 공용(아카이브 잡 6종이 같은 계약을 쓴다). */
public final class CdnUrls {

	private CdnUrls() {
	}

	/** 지금 기준 상대 초로 만료되는 서명 URL — 음수면 이미 만료. */
	public static String expiringIn(String fileName, long secondsFromNow) {
		return oe(fileName, Instant.now().getEpochSecond() + secondsFromNow);
	}

	/** oe=(hex unix 초)를 붙인 URL — 실제 인스타 URL처럼 다른 파라미터 사이에 끼워 둔다. */
	public static String oe(String fileName, long epochSecond) {
		return "https://cdn.example/v/t51/" + fileName
				+ "?stp=dst-jpg&oe=" + Long.toHexString(epochSecond).toUpperCase() + "&_nc_sid=8b3546";
	}

	/** oe가 없는 URL — 만료 미상(시도 유지, 정렬 뒤쪽). */
	public static String noOe(String fileName) {
		return "https://cdn.example/v/t51/" + fileName + "?stp=dst-jpg&_nc_sid=8b3546";
	}

	/**
	 * 만료가 무관한 픽스처용 oe 쿼리 조각(실행 시점 +10년) — 픽스처 URL의 호스트·경로를 직접 쓰고
	 * 싶을 때 {@code "...jpg?" + farFutureOe()}로 붙인다. 절대값 리터럴을 금지하는 이유:
	 * {@code oe=abc}는 hex 2748(1970년)로 파싱돼 만료 필터에 걸렸고(도입 커밋에서 실제 파손),
	 * {@code 7FFFFFFF} 같은 먼-미래 리터럴도 2038-01-19에 같은 방식으로 일제히 깨진다.
	 */
	public static String farFutureOe() {
		return "oe=" + Long.toHexString(Instant.now().getEpochSecond() + 315_360_000L).toUpperCase();
	}
}
