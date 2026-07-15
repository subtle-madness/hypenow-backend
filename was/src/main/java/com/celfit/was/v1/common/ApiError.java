package com.celfit.was.v1.common;

/** 스펙 3.2 에러 객체 — message는 사용자에게 그대로 노출 가능한 한국어 문장. */
public record ApiError(String code, String message) {
}
