package com.celfit.was.admin;

import java.util.List;

/** 가입 코드 일괄 적재 요청(설계 2026-07-20) — codes는 PREFIX-XXXX 형식, 배치 ≤500. */
public record SignupCodeCreateRequest(List<String> codes) {
}
