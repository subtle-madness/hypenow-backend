package com.celfit.was.v1.admin;

import java.time.OffsetDateTime;

/**
 * app.users 1행(어드민 조회 전용 투영, 설계 2026-08-01 §4) — GET /v1/admin/users(목록)·
 * GET /v1/admin/users/{id}(상세)가 같은 컬럼 집합을 공유한다. name·companyName은 스키마상
 * NOT NULL DEFAULT ''라 실제로는 null이 되지 않지만(V3), jobTitle·signupRoute·lastActiveAt은
 * V9 이후 nullable이라 방어적으로 null을 허용한다. featureOverrides는 jsonb 원문(::text)이라
 * 파싱은 FeatureOverridesCodec 몫이다(2026-08-31 유저별 기능 플래그).
 */
public record AdminUserRow(long id, String email, String name, String userType, String signupRoute,
		String companyName, String jobTitle, OffsetDateTime createdAt, OffsetDateTime lastActiveAt,
		String featureOverrides) {
}
