package com.celfit.monitoring.web;

/**
 * 등록 응답 본문 — 계약 §2-1. firstSnapshot 셰이프는 타입별로 다르다
 * (ACCOUNT는 `{profile, recentPostCount}`, POST는 `{post}`, replay는 null).
 */
public record RegisterResponse(long targetId, String status, Object firstSnapshot) {}
