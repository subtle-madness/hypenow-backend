package com.celfit.was.v1.account;

/** 가입 코드 사전 검증 요청(클로즈베타 설계 2026-07-19) — 관문 화면에서 코드 입력 직후 호출. */
public record SignupCodeVerifyRequest(String code) {
}
