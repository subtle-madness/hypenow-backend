package com.celfit.monitoring.web;

/**
 * 공통 에러 응답 본문 — 계약 §2 `{code, message}`. code가 계약이고 message는 사람용 설명이다.
 * (스프링의 org.springframework.web.ErrorResponse와 이름이 겹치지 않게 ApiError로 둔다.)
 */
public record ApiError(String code, String message) {}
