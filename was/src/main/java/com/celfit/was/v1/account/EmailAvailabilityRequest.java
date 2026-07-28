package com.celfit.was.v1.account;

/** 스펙 6.24 이메일 중복 확인 요청. */
public record EmailAvailabilityRequest(String email) {
}
