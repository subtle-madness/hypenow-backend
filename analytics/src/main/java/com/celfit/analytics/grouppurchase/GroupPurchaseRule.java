package com.celfit.analytics.grouppurchase;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 공동구매(공구) 규칙 판정 — "규칙이 확실한 곳은 규칙, 애매한 곳만 LLM"(스펙
 * 2026-09-03-group-purchase-judgment-design.md §3). 순수 함수라 캡션 1건마다 부작용 없이 반복
 * 호출된다. 표는 순서대로 적용하고 앞 단계에서 확정되면 뒤는 생략한다.
 *
 * <pre>
 * 1) '공동구매' 포함              → CONFIRMED_TRUE
 * 2) '#공구' 포함                 → CONFIRMED_TRUE
 * 3) '공구' 포함 + 도구 어휘 동반  → AMBIGUOUS (LLM으로)
 * 4) '공구' 포함, 도구 어휘 없음   → CONFIRMED_TRUE
 * 5) 둘 다 없음                    → CONFIRMED_FALSE
 * </pre>
 *
 * <p>도구 어휘는 근처 N자가 아니라 <b>캡션 전체</b> 대상이다 — 넉넉하게 잡아도 애매 분류로
 * 늘어나는 건 LLM 콜 몇백 건뿐이고, "드릴게요"류 오분류는 LLM이 걸러주므로 무해하다(애매 분류는
 * "보류"이지 "거짓"이 아니다 — 스펙 §3).
 */
public final class GroupPurchaseRule {

	/** 도구 어휘(애매 분류 트리거) — 스펙 §3 어휘 그대로. DIY만 대소문자 무관. */
	private static final List<String> TOOL_WORDS = List.of(
			"없이", "조립", "설치", "나사", "드릴", "망치", "볼트", "드라이버", "렌치", "톱",
			"목재", "목공", "철물", "전동", "수리", "공구함", "공구통", "공구박스", "공구세트");

	private static final String TOOL_WORD_DIY = "DIY";

	/** "공구를 들고", "공구가 필요", "공구 사용" 류 — 스펙 §3 정규식 그대로. */
	private static final Pattern TOOL_PHRASE = Pattern.compile("공구 ?(를|가) (들|필요|사용|이용|챙)");

	private static final String KEYWORD_CONFIRMED = "공동구매";
	private static final String KEYWORD_HASHTAG = "#공구";
	private static final String KEYWORD_AMBIGUOUS_TRIGGER = "공구";

	public enum Verdict { CONFIRMED_TRUE, AMBIGUOUS, CONFIRMED_FALSE }

	public record Result(Verdict verdict, String reason) {}

	private GroupPurchaseRule() {}

	/** 캡션 1건에 대해 규칙 표를 순서대로 적용한다. null·빈 캡션은 CONFIRMED_FALSE. */
	public static Result evaluate(String caption) {
		String c = caption == null ? "" : caption;
		if (c.contains(KEYWORD_CONFIRMED)) {
			return new Result(Verdict.CONFIRMED_TRUE, "'공동구매' 포함");
		}
		if (c.contains(KEYWORD_HASHTAG)) {
			return new Result(Verdict.CONFIRMED_TRUE, "'#공구' 포함");
		}
		if (c.contains(KEYWORD_AMBIGUOUS_TRIGGER)) {
			if (hasToolWord(c)) {
				return new Result(Verdict.AMBIGUOUS, "'공구' 포함 + 도구 어휘 동반 — LLM 판정 필요");
			}
			return new Result(Verdict.CONFIRMED_TRUE, "'공구' 포함, 도구 어휘 없음");
		}
		return new Result(Verdict.CONFIRMED_FALSE, "'공구'·'공동구매' 미포함");
	}

	private static boolean hasToolWord(String caption) {
		for (String word : TOOL_WORDS) {
			if (caption.contains(word)) {
				return true;
			}
		}
		if (caption.toUpperCase(Locale.ROOT).contains(TOOL_WORD_DIY)) {
			return true;
		}
		return TOOL_PHRASE.matcher(caption).find();
	}
}
