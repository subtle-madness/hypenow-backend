package com.celfit.monitoring.ad;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 광고 표기 Tier1 고신뢰 사전(스펙 §5) — 지침 원문 예시 중 <b>오탐 여지가 없는 패턴만</b> 등재한다.
 * 매칭되면 위치 규칙({@link AdPositionRule}) 통과 시 LLM 콜 없이 DISCLOSED를 확정한다
 * ({@link AdDisclosureJudgeService} 참조). "광고" 단독처럼 저정밀 패턴은 의도적으로 미등재
 * ("광고판이 예쁘네요" 오탐 — 스펙 §5).
 * <b>부정 신호와 스팬이 겹치는 후보만 제외한다 — 캡션 다른 곳의 부정 문구는 관련 없는 표기 매칭을
 * 막지 않는다.</b> ("#광고 아님"은 NEGATION 스팬이 "#광고"와 겹쳐 그 후보만 제외되지만,
 * "#광고 아린이가 … 내돈내산해서"처럼 NEGATION("내돈내산")이 "#광고"와 겹치지 않으면 "#광고"는
 * 그대로 매칭돼 DISCLOSED로 확정된다 — 08-28 ourpharm_official 계열 운영 오탐 실측 후 축소.)
 */
public final class AdDisclosurePatterns {

	private AdDisclosurePatterns() {
	}

	// 해시태그 패턴은 더 긴 해시태그의 접두만 매칭되는 사고를 막기 위해 토큰 경계를 강제한다
	// ((?![\p{L}\p{N}_]) — 다음 글자가 문자/숫자/밑줄이면 매칭 실패). 예: "#광고아님"은 "#광고"로
	// 오탐하지 않는다. "[#＃]"는 반각(U+0023)·전각(U+FF03) 해시 둘 다 인정 — 08-28 dodami_0607
	// 계열("＃협찬 | #아워팜") 전각 해시 미매칭 오탐 실측 후 추가.
	private static final List<Pattern> HIGH_CONFIDENCE = List.of(
			Pattern.compile("[#＃]유료광고(?![\\p{L}\\p{N}_])"),
			Pattern.compile("[#＃]광고(?![\\p{L}\\p{N}_])"),
			Pattern.compile("[#＃]협찬(?![\\p{L}\\p{N}_])"),
			// 지침 Ⅴ.6이 인정하는 명확 표기 — 08-19 운영 실측에서 LLM이 AMBIGUOUS로 오분류해
			// "표기 미흡" 오탐 727건이 나가 사전에 등재(핫픽스). 텍스트 단독 "제품제공"은
			// "제품제공 이벤트"(팔로워 증정 공지) 오탐 여지가 있어 해시태그형·수령형만 넣는다.
			Pattern.compile("[#＃]제품제공(?![\\p{L}\\p{N}_])"),
			// #제품제공과 동일 구조의 변형 — 08-19 오탐 전수 점검에서 Gemini 프롬프트 CLEAR 예시에만
			// 있고 사전엔 빠진 비대칭이 확인돼 정합 맞춤(현 운영 데이터 0건, 예방 등재).
			Pattern.compile("[#＃]상품제공(?![\\p{L}\\p{N}_])"),
			// 오타·변형 표기 — 08-28 운영 위험 판정 실측 8건, 전부 캡션 상단의 실제 표기 시도(오타여도
			// 소비자가 인지 가능한 명백한 표기 의도로 판단해 등재). "제품증정"·"상품증정"은 "제공"의
			// 동의어 "증정" 변형(상품증정은 제품증정의 대칭 예방 등재, 운영 데이터 무관).
			Pattern.compile("[#＃](제픔제공|재품제공|제품증정|상품증정|제품단순제공)(?![\\p{L}\\p{N}_])"),
			Pattern.compile("제품을?\\s*제공\\s*받(아|았|은|고)"),
			// "제품" 접두 없이도 과거·확정형("받았"·"받은")이면 고신뢰 — 08-28 운영 실측(NOT_DISCLOSED
			// "수딩젤도 제공받았는데"류)에서 "제품" 접두를 요구하던 위 패턴이 놓친 사례가 다수 확인됐다.
			// "받아"·"받고"는 넣지 않는다 — "제공받고 후기 작성해주실 분"처럼 서포터즈 모집 문맥이
			// 과거형이 아니면 오탐하기 때문(기존 "협찬\s*받(았|은)" 선례와 동일 원칙). 부정문
			// "제공받은 것 없는 단순 공유입니다"류는 아래 NEGATION 스팬 겹침으로 별도 방어한다.
			Pattern.compile("제공\\s*받(았|은)"),
			Pattern.compile("증정\\s*받(았|은)"),
			Pattern.compile("광고입니다"),
			Pattern.compile("유료\\s*광고"),
			Pattern.compile("대가성\\s*광고"),
			// "협찬받고"(모집·희망) 오탐 방지 — 과거형 확정 문구만("협찬받았", "협찬받은").
			// "협찬받아 작성" 류는 Tier1에서 빠지지만 LLM(Tier2)이 처리해 정확도 손실은 없다.
			Pattern.compile("협찬\\s*받(았|은)"),
			Pattern.compile("제공받아\\s*작성"),
			Pattern.compile("소정의\\s*(수수료|원고료|광고료)"),
			// 괄호형 표기("(광고)", "[협찬]") — 08-28 ourpharm_official "(광고) 지만 내돈내산" 운영
			// 오탐 실측 후 등재. "광고" 단독은 여전히 미등재("광고판이 예쁘네요" 오탐 방지) — 괄호로
			// 감싼 형태만 고정밀이라 예외로 인정한다.
			Pattern.compile("[\\(\\[]\\s*광고\\s*[\\)\\]]"),
			Pattern.compile("[\\(\\[]\\s*협찬\\s*[\\)\\]]"));

	// 부정·자비 구매 신호 — 매칭된 표기 후보의 스팬과 겹칠 때만 그 후보를 제외한다(캡션 어디든
	// 있으면 전체를 포기하던 과거 방식은 "#광고 …(무관한 문장)… 내돈내산" 같은 캡션에서 실존하는
	// 표기까지 함께 죽여 08-28 운영 오탐(_arinzip·_bbohouse)을 냈다). Tier1이 낼 수 있는 최악의
	// 오류(false DISCLOSED)를 막는 목적은 그대로 유지 — "#광고 아님"처럼 부정어가 표기 문구를
	// 직접 수식하는 스팬 겹침 케이스만 제외한다.
	// "(제공|증정|선물)받(은|는|을)? (것|거|건)?(도|은|는)? (없|아니…)"(08-28 추가) — "제공받은 것
	// 없는 단순 공유입니다"(운영 2건 실측)·"제공받은 건 아니고"류가 위 "제공\s*받(았|은)" 신규
	// 패턴에 false DISCLOSED 되는 걸 막는다. 부정문 스팬이 Tier1 후보 스팬과 겹쳐 그 후보만
	// 제외된다. 부정 꼬리는 한글 음절 조합 때문에 "아니"만으론 "아닌데"(아+닌+데)·"아님"을 못
	// 잡아 활용형(아닌|아님)을 병기한다.
	private static final Pattern NEGATION = Pattern.compile(
			"내돈내산|(광고|협찬)\\s*(이|가|은|는)?\\s*(아니|아님)"
					+ "|(제공|증정|선물)\\s*받(은|는|을)?\\s*(것|거|건)?(도|은|는)?\\s*(없|아니|아닌|아님)");

	/** 매칭 문구·문자 오프셋 — 오프셋은 그래핌이 아니라 char index(호출부가 위치 판정 시 변환). */
	public record Match(String phrase, int start, int end) {
	}

	/**
	 * 캡션 전체에서 가장 이른 위치의 고신뢰 매칭 1건. 여러 패턴이 매칭돼도 등장 순서로만 고른다.
	 * 부정 신호({@link #NEGATION}) 스팬과 겹치는 후보는 제외하고, 겹치지 않는 후보 중 가장 이른
	 * 위치를 반환한다. 모든 후보가 제외되거나 애초에 매칭이 없으면 null이다.
	 */
	public static Match findFirstMatch(String caption) {
		if (caption == null || caption.isBlank()) {
			return null;
		}
		List<int[]> negationSpans = new ArrayList<>();
		Matcher negationMatcher = NEGATION.matcher(caption);
		while (negationMatcher.find()) {
			negationSpans.add(new int[] {negationMatcher.start(), negationMatcher.end()});
		}
		Match best = null;
		for (Pattern pattern : HIGH_CONFIDENCE) {
			Matcher matcher = pattern.matcher(caption);
			while (matcher.find()) {
				int start = matcher.start();
				int end = matcher.end();
				if (overlapsAny(negationSpans, start, end)) {
					continue;
				}
				if (best == null || start < best.start()) {
					best = new Match(matcher.group(), start, end);
				}
			}
		}
		return best;
	}

	private static boolean overlapsAny(List<int[]> spans, int start, int end) {
		for (int[] span : spans) {
			if (start < span[1] && span[0] < end) {
				return true;
			}
		}
		return false;
	}
}
