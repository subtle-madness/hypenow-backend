package com.celfit.monitoring.domain;

/** 브랜드 계정 추적 상태 — 가입 시 ACTIVE로 시작, 탈퇴 시 CLOSED(휴면 완화 없음 — 태그 스펙 §8). */
public enum BrandStatus { ACTIVE, CLOSED }
