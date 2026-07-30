package com.celfit.was.v1.monitoring;

/**
 * 댓글 작성자 계정명 마스킹(계약 6.25 PostComment) — 원본 핸들을 응답에 실으면 방침 위반이라
 * 응답 생성 단계(서버)에서 마스킹한다. 규칙(6.26 어셈블러 태스크에서 확정): 앞 2자 유지 + {@code ***}
 * + 끝 2자 유지, 총 길이가 4 이하면 첫 글자 + {@code ***}. 예: {@code glowdeep_92} → {@code gl***92}.
 */
public final class AuthorMask {

	private static final String MASK = "***";

	private AuthorMask() {
	}

	public static String mask(String author) {
		if (author == null) {
			return null;
		}
		int length = author.length();
		if (length <= 4) {
			String first = length == 0 ? "" : author.substring(0, 1);
			return first + MASK;
		}
		return author.substring(0, 2) + MASK + author.substring(length - 2);
	}
}
