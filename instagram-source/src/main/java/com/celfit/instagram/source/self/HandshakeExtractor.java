package com.celfit.instagram.source.self;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GraphQL 핸드셰이크 재료 추출(crawler HandshakeExtractor 이식) — 게시물 페이지 HTML의 LSD 토큰과
 * shortcode→media pk 산술 변환. LSD가 없는 200 본문은 로그인 벽 셸이 정상 원인이라
 * LOGIN_WALL로 분류한다(표면 소진 라우팅).
 */
public final class HandshakeExtractor {

	// __d("LSD",[],{"token":"AdT..."} — 게시물 페이지 인라인 스크립트의 CSRF 대용 토큰.
	private static final Pattern LSD = Pattern.compile("\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"");
	// IG shortcode는 base64url 변형 알파벳의 64진수 표현 — pk = Σ digit·64^i.
	private static final String ALPHABET =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

	private HandshakeExtractor() {}

	public static String lsdFrom(String html) {
		Matcher m = LSD.matcher(html == null ? "" : html);
		if (!m.find()) {
			throw new SelfCrawlException(SelfErrorClass.LOGIN_WALL, "lsd 추출 실패");
		}
		return m.group(1);
	}

	public static long mediaIdFromShortCode(String sc) {
		long n = 0;
		for (int i = 0; i < sc.length(); i++) {
			int digit = ALPHABET.indexOf(sc.charAt(i));
			if (digit < 0) {
				throw new IllegalArgumentException("shortcode 알파벳 밖 문자: " + sc);
			}
			n = n * 64 + digit;
		}
		return n;
	}
}
