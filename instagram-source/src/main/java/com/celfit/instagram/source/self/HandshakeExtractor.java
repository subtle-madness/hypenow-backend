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

	/**
	 * 게시물 페이지 SSR HTML에 인라인된 {@code "comments_connection":{...}} JSON 블록을 통째로
	 * 잘라 돌려준다(1페이지 댓글 — doc_id 없이 얻는다, 실측: 스크래치패드 page_source.html). LSD가
	 * 없는 페이지와 같은 원인(로그인 벽 셸)일 가능성이 커 못 찾으면 LOGIN_WALL로 분류한다.
	 */
	public static String commentsConnectionFrom(String html) {
		String json = balancedObjectAfter(html, "comments_connection");
		if (json == null) {
			throw new SelfCrawlException(SelfErrorClass.LOGIN_WALL, "comments_connection 추출 실패");
		}
		return json;
	}

	/**
	 * {@code "<key>":{ ... }} 형태를 찾아, 문자열 리터럴(이스케이프 포함) 내부의 중괄호는 세지 않고
	 * 깊이가 0으로 돌아오는 지점까지 잘라 JSON 객체 원문을 돌려준다. 못 찾으면 null.
	 */
	private static String balancedObjectAfter(String html, String key) {
		if (html == null) {
			return null;
		}
		String marker = "\"" + key + "\":";
		int markerIdx = html.indexOf(marker);
		if (markerIdx < 0) {
			return null;
		}
		int start = html.indexOf('{', markerIdx + marker.length());
		if (start < 0) {
			return null;
		}
		boolean inString = false;
		boolean escaped = false;
		int depth = 0;
		for (int i = start; i < html.length(); i++) {
			char c = html.charAt(i);
			if (inString) {
				if (escaped) {
					escaped = false;
				} else if (c == '\\') {
					escaped = true;
				} else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
			} else if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return html.substring(start, i + 1);
				}
			}
		}
		return null;
	}
}
