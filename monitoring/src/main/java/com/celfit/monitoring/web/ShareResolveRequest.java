package com.celfit.monitoring.web;

/**
 * share 해소 요청 본문 — 계약 §2-6. userId는 콜 집계 귀속용 옵션 필드(2026-08-12 비용 범위 확장) —
 * 구 was가 안 보내도 해소는 동작한다(그 콜만 비용 미집계).
 */
public record ShareResolveRequest(String url, Long userId) {}
