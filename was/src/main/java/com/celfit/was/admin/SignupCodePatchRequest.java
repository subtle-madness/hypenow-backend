package com.celfit.was.admin;

/**
 * 가입 코드 부분 갱신 요청(설계 2026-07-22 발송 표시 → 2026-08-04 super 승격·강등 확장).
 * 두 필드 모두 옵션(Boolean 래퍼) — 온 필드만 갱신하고, 둘 다 null이면 400.
 */
public record SignupCodePatchRequest(Boolean isSent, Boolean isSuper) {
}
