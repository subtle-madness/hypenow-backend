package com.celfit.was.admin;

/** 적재 결과 — inserted=신규 저장 수, skipped=중복 등으로 건너뛴 수(제출 수 − inserted). */
public record SignupCodeCreateResponse(int inserted, int skipped) {
}
