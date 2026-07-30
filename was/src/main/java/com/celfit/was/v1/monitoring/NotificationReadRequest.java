package com.celfit.was.v1.monitoring;

import java.util.List;

/**
 * POST /v1/notifications/read 요청 본문(스펙 6.32) — {@code {"ids":[...]}} 또는
 * {@code {"all":true}}. 둘 다 없으면(또는 all=false만 오면) 컨트롤러가 400 VALIDATION_FAILED로
 * 거절한다. ids 원소는 계약상 문자열(id는 문자열)이며 숫자 변환 실패 원소는 컨트롤러가
 * "존재하지 않는 id"로 무시한다.
 */
public record NotificationReadRequest(List<String> ids, Boolean all) {
}
