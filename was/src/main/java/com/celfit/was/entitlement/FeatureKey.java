package com.celfit.was.entitlement;

/**
 * 기능 키 정본 레지스트리 — entitlement 게이트({@link Entitlements#has})와 어드민 오버라이드 API가 참조하는
 * 유일한 소스. 1차 범위는 판정 인프라까지라 제품 키는 아직 없다(설계 2026-08-17 "1차 범위는 인프라까지").
 * 계약 내용이 확정되면 이 enum에 상수를 추가한다 — {@link PlanDefaults}의 plan별 기본값,
 * {@code /v1/admin/organizations/{id}/overrides/{featureKey}}의 유효 키 검증이 자동으로 그 키를 인식한다.
 * DB(app.organization_feature_overrides.feature_key)에 남아있지만 이 enum에 없는 문자열은
 * {@link EntitlementService#compose}가 무시하고 warn 로그를 남긴다(키 삭제 후 잔재 행 대비).
 */
public enum FeatureKey {
}
