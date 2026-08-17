package com.celfit.monitoring.ad;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 광고 표기 Tier1 고신뢰 사전(스펙 §5) — 지침 원문 예시 중 <b>오탐 여지가 없는 패턴만</b> 등재한다.
 * 매칭되면 위치 규칙({@link AdPositionRule}) 통과 시 LLM 콜 없이 DISCLOSED를 확정한다
 * ({@link AdDisclosureJudgeService} 참조). "광고" 단독처럼 저정밀 패턴은 의도적으로 미등재
 * ("광고판이 예쁘네요" 오탐 — 스펙 §5).
 */
public final class AdDisclosurePatterns {

	private AdDisclosurePatterns() {
	}

	private static final List<Pattern> HIGH_CONFIDENCE = List.of(
			Pattern.compile("#유료광고"),
			Pattern.compile("#광고"),
			Pattern.compile("#협찬"),
			Pattern.compile("광고입니다"),
			Pattern.compile("유료\\s*광고"),
			Pattern.compile("대가성\\s*광고"),
			Pattern.compile("협찬받"),
			Pattern.compile("제공받아\\s*작성"),
			Pattern.compile("소정의\\s*(수수료|원고료|광고료)"));

	/** 매칭 문구·문자 오프셋 — 오프셋은 그래핌이 아니라 char index(호출부가 위치 판정 시 변환). */
	public record Match(String phrase, int start, int end) {
	}

	/** 캡션 전체에서 가장 이른 위치의 고신뢰 매칭 1건. 여러 패턴이 매칭돼도 등장 순서로만 고른다. */
	public static Match findFirstMatch(String caption) {
		if (caption == null || caption.isBlank()) {
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
