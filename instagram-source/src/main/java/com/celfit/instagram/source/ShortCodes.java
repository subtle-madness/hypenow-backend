package com.celfit.instagram.source;

/**
 * 인스타 숏코드 ↔ 미디어 pk 변환 — base64 유사 인코딩(알파벳 A-Za-z0-9-_, 최상위 자리부터).
 * media_pk는 저장하지 않고 댓글 콜(media/comments) 시점에 shortcode에서 산술 유도한다(findings §10-1).
 * 실측 검증값: "DbV7LgZsKG8" → 3951324523536622012L.
 */
public final class ShortCodes {

	private static final String ALPHABET =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

	private ShortCodes() {
	}

	/** shortcode → media pk. 64진수 값으로 해석한다(자리마다 6비트, 최상위 문자부터). */
	public static long toMediaId(String shortCode) {
		long id = 0;
		for (int i = 0; i < shortCode.length(); i++) {
			int digit = ALPHABET.indexOf(shortCode.charAt(i));
			if (digit < 0) {
				throw new IllegalArgumentException("숏코드에 알 수 없는 문자: " + shortCode);
			}
			id = id * 64 + digit;
		}
		return id;
	}
}
