package com.celfit.was.v1.notices;

/** GET /v1/notices/seen 응답 — 확인 이력이 없으면 lastSeenAt은 명시적 null(계약 무결성 규칙 #1). */
public record NoticeSeenResponse(String lastSeenAt) {
}
