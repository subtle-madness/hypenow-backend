package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;

/**
 * 챗 요청(설계 §5) - 무상태라 대화 이력 전체를 매 요청에 싣는다. 서버 세션은 스코프 밖(설계 §10).
 * 검증(빈 목록·역할·길이·건수)은 컨트롤러가 수동으로 한다(브랜드 표면의 기존 관용구).
 */
public record AiChatRequest(List<AiChatMessage> messages) {
}
