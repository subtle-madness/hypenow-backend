package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;

/**
 * 챗 응답(설계 §5) - 답변 텍스트와 참조한 게시물 shortCode 목록. 프론트가 shortCode로 링크를
 * 걸 수 있게 별도 필드로 뺀다(본문 파싱을 시키지 않는다).
 */
public record AiChatResponse(String answer, List<String> referencedShortCodes) {
}
