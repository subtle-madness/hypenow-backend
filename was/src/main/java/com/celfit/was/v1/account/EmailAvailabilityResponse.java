package com.celfit.was.v1.account;

/** 스펙 6.24 — available: true = 가입 가능, false = 이미 가입된 이메일. */
public record EmailAvailabilityResponse(boolean available) {
}
