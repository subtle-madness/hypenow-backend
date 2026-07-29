package com.celfit.monitoring.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 등록 응답 본문 — 계약 §2-1. firstSnapshot 셰이프는 타입별로 다르다
 * (ACCOUNT는 `{profile, recentPostCount}`, POST는 `{post}`).
 * replay(200)에는 첫 스냅샷이 없어서 NON_NULL로 키 자체를 생략한다 — `null` 값을 내려보내는 것과 구분된다.
 */
public record RegisterResponse(long targetId, String status,
		@JsonInclude(JsonInclude.Include.NON_NULL) Object firstSnapshot) {}
