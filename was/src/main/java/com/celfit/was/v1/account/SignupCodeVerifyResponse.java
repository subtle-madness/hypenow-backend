package com.celfit.was.v1.account;

/** 가입 코드 사전 검증 응답 — 유효하지 않으면 403 INVALID_SIGNUP_CODE라 valid는 항상 true지만, 프론트 계약 형상을 유지한다. */
public record SignupCodeVerifyResponse(boolean valid) {
}
