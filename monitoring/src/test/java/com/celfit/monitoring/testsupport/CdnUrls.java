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
}
