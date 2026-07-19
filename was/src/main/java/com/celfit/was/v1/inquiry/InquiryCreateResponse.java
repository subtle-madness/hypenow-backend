package com.celfit.was.v1.inquiry;

/** 도입문의 접수 응답 — id는 uuid 문자열(순번 노출 회피, V10 설계 주석 참조). */
public record InquiryCreateResponse(String id) {
}
