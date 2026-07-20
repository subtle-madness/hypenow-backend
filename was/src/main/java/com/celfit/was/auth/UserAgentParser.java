package com.celfit.was.auth;

/** User-Agent 1회 파싱 — 주요 패턴 매칭만(라이브러리 불요, 스펙 6.14의 browser/os 표기용). */
public final class UserAgentParser {

	private UserAgentParser() {
	}

	public static String browser(String ua) {
		if (ua == null) return "기타";
		if (ua.contains("Edg/")) return "Edge";
		if (ua.contains("Chrome/") && !ua.contains("Chromium")) return "Chrome";
		if (ua.contains("Firefox/")) return "Firefox";
		if (ua.contains("Safari/") && ua.contains("Version/")) return "Safari";
		return "기타";
	}

	public static String os(String ua) {
		if (ua == null) return "기타";
		if (ua.contains("Windows")) return "Windows";
		if (ua.contains("Mac OS X") && !ua.contains("iPhone") && !ua.contains("iPad")) return "Mac OS X";
		if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
		if (ua.contains("Android")) return "Android";
		if (ua.contains("Linux")) return "Linux";
		return "기타";
	}
}
