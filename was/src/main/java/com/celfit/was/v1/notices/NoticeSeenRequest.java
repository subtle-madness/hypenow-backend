package com.celfit.was.v1.notices;

/** PUT /v1/notices/seen 요청 본문 — 값은 저장만 한다(역행 가드 없음, 요청서 "저장만 하면 됩니다"). */
public record NoticeSeenRequest(String lastSeenAt) {
}
