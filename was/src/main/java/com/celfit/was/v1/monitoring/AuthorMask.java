package com.celfit.was.v1.monitoring;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 댓글 작성자 계정명 마스킹(계약 6.25 PostComment) — 원본 핸들을 응답에 실으면 방침 위반이라
 * 응답 생성 단계(서버)에서 마스킹한다. 규칙(6.26 어셈블러 태스크에서 확정): 앞 2자 유지 + {@code ***}
 * + 끝 2자 유지, 총 길이가 4 이하면 첫 글자 + {@code ***}. 예: {@code glowdeep_92} → {@code gl***92}.
 */
public final class AuthorMask {

	private static final String MASK = "***";

	/**
	 * 답글 본문 선행 멘션 — 인스타그램이 답글 앞에 상대(댓글 작성자) 핸들을 자동으로 붙인다
	 * ({@code @nunu.zip_ 감사합니다}). IG 핸들 문자 집합(영숫자·밑줄·점)만 허용한다.
	 */
	private static final Pattern LEADING_MENTION = Pattern.compile("^@([A-Za-z0-9._]+)");

	private AuthorMask() {
	}

	/**
	 * 답글 본문의 선행 {@code @핸들}을 {@link #mask}와 같은 규칙으로 가린다(2026-09-03, FE 피드백
	 * 09-01 #4-D — author는 {@code nu***p_}인데 reply.text가 {@code @nunu.zip_}로 시작해 원본 핸들이
	 * 새던 결함; 조사 33건 전부 이 패턴). 방침(제3장 ②)은 "작성자 계정명 마스킹"이므로 가리기를
	 * 유지하고 본문 쪽 누출만 막는다. 연속 멘션({@code @a @b 본문})도 앞에서부터 전부 가린다.
	 * 본문 중간의 멘션은 손대지 않는다 — 자동 선행 멘션이 아니라 작성자가 쓴 텍스트다.
	 *
	 * @param replyText 답글 본문. null이면 null.
	 */
	public static String maskReply(String replyText) {
		if (replyText == null) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		String rest = replyText;
		while (true) {
			Matcher m = LEADING_MENTION.matcher(rest);
			if (!m.find()) {
				break;
			}
			out.append('@').append(mask(m.group(1)));
			rest = rest.substring(m.end());
			// 멘션 사이 공백은 그대로 옮기고 다음 토큰이 또 멘션이면 계속 가린다.
			int i = 0;
			while (i < rest.length() && rest.charAt(i) == ' ') {
				i++;
			}
			if (i == 0 || i >= rest.length() || rest.charAt(i) != '@') {
				break;
			}
			out.append(rest, 0, i);
			rest = rest.substring(i);
		}
		return out.append(rest).toString();
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
