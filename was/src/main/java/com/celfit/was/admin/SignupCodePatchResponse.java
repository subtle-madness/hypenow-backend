package com.celfit.was.admin;

/**
 * 가입 코드 부분 갱신 결과 — 반영된 최종 상태를 그대로 돌려준다(멱등 PATCH).
 * JdbcClient 매핑: is_sent → isSent, is_super → isSuper.
 */
public record SignupCodePatchResponse(String code, boolean isSent, boolean isSuper) {
}
