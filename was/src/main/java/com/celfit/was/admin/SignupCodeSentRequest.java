package com.celfit.was.admin;

/** 발송 표시 변경 요청(설계 2026-07-22) — Boolean 래퍼로 받아 누락(null)을 400으로 구분한다. */
public record SignupCodeSentRequest(Boolean isSent) {
}
