package com.celfit.was.v1.influencer;

import java.util.regex.Pattern;

/**
 * 공동구매 시그널 판정 — celfit-front {@code src/lib/discover/group-purchase.ts}와 정확히 동일한
 * 규칙(정규식 {@code /공동구매|#공구/}의 find() 매칭)을 Java에서 재현한다. FE는 상세 API(6.4)의
 * recentContents 캡션 12개에 이 규칙을 적용해 뱃지를 그리므로, 여기서도 같은 정규식·같은 find()
 * 의미(부분일치, 앵커 없음)를 그대로 써야 두 화면의 판정이 어긋나지 않는다.
 *
 * "공동구매"는 캡션 어디에 있든 매칭되지만, "공구"는 반드시 "#" 바로 뒤에 올 때만 매칭된다
 * ("#공구오픈"처럼 뒤에 다른 문자가 붙는 접두 매칭은 인정 — find()가 앵커 없이 부분일치하므로).
 * "#" 없이 맨몸으로 등장하는 "공구"(예: "메이크업 공구 정리했어요")는 매칭되지 않는다.
 * 캡션이 null이면 미매칭으로 취급한다.
 */
public final class GroupPurchaseSignal {

	private static final Pattern PATTERN = Pattern.compile("공동구매|#공구");

	private GroupPurchaseSignal() {
	}

	public static boolean matches(String caption) {
		return caption != null && PATTERN.matcher(caption).find();
	}
}
