package com.celfit.was.admin;

/** 발송 표시 변경 결과(설계 2026-07-22) — 반영된 최종 상태를 그대로 돌려준다(멱등 PATCH). */
public record SignupCodeSentResponse(String code, boolean isSent) {
}
