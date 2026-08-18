package com.celfit.monitoring.ad;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 광고 표기 Tier1 고신뢰 사전(스펙 §5) — 지침 원문 예시 중 <b>오탐 여지가 없는 패턴만</b> 등재한다.
 * 매칭되면 위치 규칙({@link AdPositionRule}) 통과 시 LLM 콜 없이 DISCLOSED를 확정한다
 * ({@link AdDisclosureJudgeService} 참조). "광고" 단독처럼 저정밀 패턴은 의도적으로 미등재
 * ("광고판이 예쁘네요" 오탐 — 스펙 §5).
 * <b>부정 신호가 보이면 Tier1은 확정하지 않는다 — 문맥 판단은 LLM 몫이다.</b>
 * ("#광고아님 내돈내산", "내돈내산이지만 #광고" 같은 캡션은 {@link #NEGATION}에 걸려 null을 반환하고
 * LLM(Tier2)로 넘어간다 — Tier1은 false DISCLOSED를 내느니 판단을 보류한다.)
 */
public final class AdDisclosurePatterns {

	private AdDisclosurePatterns() {
	}

	// 해시태그 패턴은 더 긴 해시태그의 접두만 매칭되는 사고를 막기 위해 토큰 경계를 강제한다
	// ((?![\p{L}\p{N}_]) — 다음 글자가 문자/숫자/밑줄이면 매칭 실패). 예: "#광고아님"은 "#광고"로
	// 오탐하지 않는다.
	private static final List<Pattern> HIGH_CONFIDENCE = List.of(
			Pattern.compile("#유료광고(?![\\p{L}\\p{N}_])"),
			Pattern.compile("#광고(?![\\p{L}\\p{N}_])"),
			Pattern.compile("#협찬(?![\\p{L}\\p{N}_])"),
			Pattern.compile("광고입니다"),
			Pattern.compile("유료\\s*광고"),
			Pattern.compile("대가성\\s*광고"),
			// "협찬받고"(모집·희망) 오탐 방지 — 과거형 확정 문구만("협찬받았", "협찬받은").
			// "협찬받아 작성" 류는 Tier1에서 빠지지만 LLM(Tier2)이 처리해 정확도 손실은 없다.
			Pattern.compile("협찬\\s*받(았|은)"),
			Pattern.compile("제공받아\\s*작성"),
			Pattern.compile("소정의\\s*(수수료|원고료|광고료)"));

	// 캡션 어디든 부정·자비 구매 신호가 하나라도 있으면 Tier1 확정을 포기하고 LLM(Tier2)으로 넘긴다.
	// 이건 NOT_DISCLOSED 확정이 아니라 "판단 보류"다 — Tier1이 낼 수 있는 최악의 오류(false
	// DISCLOSED)를 막기 위한 가드일 뿐, 부정 문구 자체가 미표기를 의미하지 않는다.
	private static final Pattern NEGATION =
			Pattern.compile("내돈내산|(광고|협찬)\\s*(이|가|은|는)?\\s*(아니|아님)");

	/** 매칭 문구·문자 오프셋 — 오프셋은 그래핌이 아니라 char index(호출부가 위치 판정 시 변환). */
	public record Match(String phrase, int start, int end) {
	}

	/**
	 * 캡션 전체에서 가장 이른 위치의 고신뢰 매칭 1건. 여러 패턴이 매칭돼도 등장 순서로만 고른다.
	 * 부정 신호({@link #NEGATION})가 캡션 어디든 있으면 Tier1을 포기하고 null을 반환한다.
	 */
	public static Match findFirstMatch(String caption) {
		if (caption == null || caption.isBlank()) {
			return null;
		}
		if (NEGATION.matcher(caption).find()) {
			return null;
		}
		Match best = null;
		for (Pattern pattern : HIGH_CONFIDENCE) {
			Matcher matcher = pattern.matcher(caption);
			if (matcher.find() && (best == null || matcher.start() < best.start())) {
				best = new Match(matcher.group(), matcher.start(), matcher.end());
			}
		}
		return best;
	}
}
