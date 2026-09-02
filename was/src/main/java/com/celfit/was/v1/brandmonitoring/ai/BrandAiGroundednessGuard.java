package com.celfit.was.v1.brandmonitoring.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 날조(ungrounded) 답변 판정(서버 groundedness 가드, 2026-09-02) - 스펙 §4 "고지는 서버 강제"의
 * 연장이다. 질문 "시딩 우선순위 정하려는데 기준 좀 잡아줘"에 gemini-3.1-flash-lite가 툴을 한 번도
 * 호출하지 않은 채 가짜 계정명·수치 표를 날조한 사례가 있었다 - BrandAiGlossary 지시를 2차례
 * 강화해도 재현돼 프롬프트 계층으로는 못 막는 사례로 확인됐다. 104턴 스윕 실측: "그 턴의 툴 호출 0회
 * + 답변에 마크다운 표(또는 세션 브랜드가 아닌 @핸들)"라는 신호가 이 날조 1건에만 걸리고 정상 답변엔
 * 0건이었다(오탐 0) - 이 신호를 서버가 결정론으로 잡는다.
 *
 * <p>{@link BrandAiAgent} 루프에서 떼어낸 순수 함수라 입출력만으로 단위 테스트한다.
 */
final class BrandAiGroundednessGuard {

	/** 줄 시작 "|"로 열고 "|"로 닫는 행 - 마크다운 표 행(헤더·구분선·데이터 행 모두 포함)만 잡는다. */
	private static final Pattern MARKDOWN_TABLE_ROW = Pattern.compile("(?m)^\\s*\\|.*\\|\\s*$");

	/** 인스타그램 계정명 형태의 @핸들. */
	private static final Pattern HANDLE = Pattern.compile("@[A-Za-z0-9_.]{3,}");

	private BrandAiGroundednessGuard() {
	}

	/**
	 * @param answer                모델이 낸 최종 답변 텍스트.
	 * @param toolCallCountThisTurn 이번 실행(run() 1회, 프리셋 선실행 주입분 포함)에서 지금까지 실행된
	 *                               툴 호출 총수. 1회 이상이면 근거가 있다고 보고 무조건 false다(툴
	 *                               결과를 실제로 근거로 썼는지는 이 가드 범위 밖).
	 * @param sessionBrandUsername   세션에 고정된 브랜드의 username(대소문자 무시 비교) - 답변에 이
	 *                               핸들만 나오면 정상 자기 언급으로 보고 예외로 둔다. null이면 예외
	 *                               없이 등장하는 모든 @핸들을 날조 신호로 본다.
	 */
	static boolean ungrounded(String answer, int toolCallCountThisTurn, String sessionBrandUsername) {
		if (toolCallCountThisTurn > 0 || answer == null || answer.isBlank()) {
			return false;
		}
		if (MARKDOWN_TABLE_ROW.matcher(answer).find()) {
			return true;
		}
		String sessionHandle = sessionBrandUsername == null ? null : "@" + sessionBrandUsername;
		Matcher matcher = HANDLE.matcher(answer);
		while (matcher.find()) {
			if (sessionHandle == null || !matcher.group().equalsIgnoreCase(sessionHandle)) {
				return true;
			}
		}
		return false;
	}
}
