package com.celfit.was.v1.inquiry;

/**
 * 도입문의 요청(클로즈베타 설계 2026-07-19) — 전 필드 필수.
 * organization은 유형별 소속명(브랜드명/대행사명/유통사명/계정명) — 가입 companyName과 동일 해석.
 */
public record InquiryRequest(String userType, String name, String email, String organization, String message) {
}
