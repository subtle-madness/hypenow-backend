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
 * <p>도구 어휘는 근처 N자가 아니라 <b>캡션 전체</b> 대상이다. 단 일상어와 겹치는 두 어휘는 좁혀
 * 잡는다(2026-09-04 운영 실측: 후보 4,808건 중 애매 1,717건의 원인이 "드릴게요"류 995건과
 * "부담없이"류 957건 — 도구와 무관한데 LLM 콜 1,700건과 오탐 2건을 만들었다. 좁힌 뒤 애매 239건):
 * "드릴"은 동사 활용("드릴게요·드릴까요·드릴 수")을 제외한 명사형만, "없이"는 "공구 없이"만.
 */
public final class GroupPurchaseRule {

	/**
	 * 도구 어휘(애매 분류 트리거) — 스펙 §3 어휘에서 "없이"·"드릴"을 뺀 부분 문자열 매칭. DIY만
	 * 대소문자 무관. 뺀 둘은 아래 정규식으로 좁혀 잡는다(클래스 javadoc의 운영 실측 참조).
	 */
	private static final List<String> TOOL_WORDS = List.of(
			"조립", "설치", "나사", "망치", "볼트", "드라이버", "렌치", "톱",
			"목재", "목공", "철물", "전동", "수리", "공구함", "공구통", "공구박스", "공구세트");

	/** 명사형 "드릴"만 — "드릴게요·드릴께요·드릴까요·드릴 수·드릴지·드릴테니·드릴려고"는 동사 활용이라 제외. */
	private static final Pattern TOOL_DRILL_NOUN = Pattern.compile("드릴(?! ?(게|께|까|수|지|테|려))");

	/** "없이"는 도구 문맥인 "공구 없이"만 — "부담없이·고민없이"는 일상어. */
	private static final Pattern TOOL_WITHOUT = Pattern.compile("공구 ?없이");

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
		return TOOL_DRILL_NOUN.matcher(caption).find()
				|| TOOL_WITHOUT.matcher(caption).find()
				|| TOOL_PHRASE.matcher(caption).find();
	}
}
