package com.celfit.was.admin;

import java.util.List;

/**
 * 가입 코드 일괄 적재 요청(설계 2026-07-20) — codes는 PREFIX-XXXX 형식, 배치 ≤500.
 * isSuper(설계 2026-08-04)는 배치 전체에 적용 — 생략·null이면 일반(1회용) 코드.
 * 'super'는 Java 예약어라 컴포넌트명으로 못 쓴다.
 */
public record SignupCodeCreateRequest(List<String> codes, Boolean isSuper) {
}
