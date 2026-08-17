package com.celfit.monitoring.ad;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 광고 표기 위치 판정(스펙 §5 Tier3, 지침 다.(2)) — '더보기' 접힘은 렌더링 기준(기기·폰트·이모지 폭)이라
 * 텍스트만으로 정확 판정이 불가능하다. 근사임을 인정하고 <b>불확실성은 전부 "위반 아님" 쪽으로</b>
 * 떨어지게 설계한다(지침 원문도 "눌러야만 확인 가능한 경우"만 부적절로 규정 — 회색지대는 게시자에게 유리).
 *
 * <p>경계값(VISIBLE_UPPER_BOUND·HIDDEN_LOWER_BOUND)은 초기값이다 — IG 피드 캡션의 실측 "더보기"
 * 절단 지점(~125자)을 참고했을 뿐, 골드셋 단계(스펙 §10-2)에서 실기기 실측으로 캘리브레이션한다.
 *
 * <p>캘리브레이션 대상은 그래핌 경계 상수 2개(VISIBLE_UPPER_BOUND·HIDDEN_LOWER_BOUND)뿐이다 — 줄 수
 * 상수(VISIBLE_LINE_MAX·HIDDEN_LINE_MIN)는 실기기 렌더링 근사가 아니라 지침 해석("첫 2줄까지는 보임"
 * 류)이라 고정값으로 두고 private로 감춘다.
 */
public final class AdPositionRule {

	private AdPositionRule() {
	}

	/** 캡션 시작부터 문구 끝까지의 그래핌 오프셋이 이 값 이내면 확실히 보임(VISIBLE). 캘리브레이션 전 초기값. */
	public static final int VISIBLE_UPPER_BOUND_GRAPHEMES = 125;
	/** 시작 오프셋이 이 그래핌 수를 넘거나 3번째 줄 이후면 확실히 접힘(HIDDEN). 캘리브레이션 전 초기값. */
	public static final int HIDDEN_LOWER_BOUND_GRAPHEMES = 220;
	private static final int VISIBLE_LINE_MAX = 2;
	private static final int HIDDEN_LINE_MIN = 3;

	private static final Pattern FIRST_HASHTAG = Pattern.compile("#[\\p{L}\\p{N}_]+");

	public enum Band { VISIBLE, GRAY, HIDDEN, FIRST_HASHTAG }

	/** start·end는 char index(String.indexOf 등 표준 자바 인덱스) — 그래핌 변환은 내부에서 한다. */
	public static Band evaluate(String caption, int start, int end) {
		if (isFirstHashtag(caption, start)) {
			return Band.FIRST_HASHTAG;
		}
		int startGrapheme = graphemeOffset(caption, start);
		int endGrapheme = graphemeOffset(caption, end);
		int startLine = lineOf(caption, start);
		// VISIBLE은 end 그래핌 기준(문구가 개행을 가로질러 접힘권으로 넘어가며 끝나는 경우도 위반으로
		// 밀지 않는다), HIDDEN은 start 기준(문구 시작만 확실히 접혔으면 접힘으로 본다) — 이 비대칭은
		// "불확실성은 위반 아님 쪽" 설계 원칙의 의도된 결과다.
		boolean visible = endGrapheme <= VISIBLE_UPPER_BOUND_GRAPHEMES && startLine <= VISIBLE_LINE_MAX;
		if (visible) {
			return Band.VISIBLE;
		}
		boolean hidden = startGrapheme > HIDDEN_LOWER_BOUND_GRAPHEMES || startLine >= HIDDEN_LINE_MIN;
		return hidden ? Band.HIDDEN : Band.GRAY;
	}

	/** 캡션의 첫 번째 '#' 해시태그 토큰과 시작 위치가 같으면 첫 해시태그(지침 다.(2)③ — 오프셋 무관 인정). */
	private static boolean isFirstHashtag(String caption, int start) {
		Matcher matcher = FIRST_HASHTAG.matcher(caption);
		return matcher.find() && matcher.start() == start;
	}

	/** char index → 그래핌(사용자 인지 문자) 개수. BreakIterator 캐릭터 경계 — ICU4J 의존 없이 JDK만. */
	public static int graphemeOffset(String text, int charIndex) {
		BreakIterator it = BreakIterator.getCharacterInstance(Locale.KOREAN);
		it.setText(text);
		int count = 0;
		for (int boundary = it.first(); boundary != BreakIterator.DONE && boundary < charIndex;
				boundary = it.next()) {
			count++;
		}
		return count;
	}

	/** 1-base 줄 번호 — charIndex 이전의 개행 수 + 1. */
	private static int lineOf(String text, int charIndex) {
		int line = 1;
		int limit = Math.min(charIndex, text.length());
		for (int i = 0; i < limit; i++) {
			if (text.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}
}
