package com.celfit.was.entitlement;

/**
 * 조직 계약 등급(설계 2026-08-17 조직·엔터프라이즈 entitlement §세 개의 축). app.organizations.plan CHECK
 * 제약과 1:1 대응한다. FREE는 DB에 저장되지 않는 무소속 유저 폴백값이다 — 기존 유저 백필이 필요 없다.
 */
public enum Plan {
	FREE,
	ENTERPRISE
}
