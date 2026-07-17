package com.celfit.was.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 실제 UA 문자열 샘플 → browser/os 라벨 (스펙 6.14 세션 목록 표기용). */
class UserAgentParserTest {

	private static final String CHROME_MAC =
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
					+ "Chrome/126.0.0.0 Safari/537.36";
	private static final String SAFARI_IPHONE =
			"Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
					+ "Version/17.5 Mobile/15E148 Safari/604.1";
	private static final String EDGE_WINDOWS =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
					+ "Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0";
	private static final String FIREFOX_LINUX =
			"Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0";
	private static final String CHROME_ANDROID =
			"Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 (KHTML, like Gecko) "
					+ "Chrome/126.0.0.0 Mobile Safari/537.36";

	@Test
	void 브라우저_라벨을_판별한다() {
		assertEquals("Chrome", UserAgentParser.browser(CHROME_MAC));
		assertEquals("Safari", UserAgentParser.browser(SAFARI_IPHONE));
		assertEquals("Edge", UserAgentParser.browser(EDGE_WINDOWS));
		assertEquals("Firefox", UserAgentParser.browser(FIREFOX_LINUX));
		assertEquals("Chrome", UserAgentParser.browser(CHROME_ANDROID));
	}

	@Test
	void OS_라벨을_판별한다() {
		assertEquals("Mac OS X", UserAgentParser.os(CHROME_MAC));
		assertEquals("iOS", UserAgentParser.os(SAFARI_IPHONE));
		assertEquals("Windows", UserAgentParser.os(EDGE_WINDOWS));
		assertEquals("Linux", UserAgentParser.os(FIREFOX_LINUX));
		assertEquals("Android", UserAgentParser.os(CHROME_ANDROID));
	}

	@Test
	void UA가_null이거나_미지의_문자열이면_기타다() {
		assertEquals("기타", UserAgentParser.browser(null));
		assertEquals("기타", UserAgentParser.os(null));
		assertEquals("기타", UserAgentParser.browser("curl/8.6.0"));
		assertEquals("기타", UserAgentParser.os("curl/8.6.0"));
	}
}
