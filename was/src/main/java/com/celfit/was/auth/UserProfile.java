package com.celfit.was.auth;

import java.time.OffsetDateTime;

/**
 * app.users 프로필 행(비밀 필드 제외) — v1 가입·로그인 응답(UserSummary)과 /v1/me(MeResponse) 조립용.
 * AppUserDetails는 안정 형상(userId, email)이라 프로필은 매 요청 DB에서 이 record로 읽는다.
 * 필드 순서는 NewUser와 동일 + 파생 필드(agreedMarketing 이후)가 뒤따른다.
 * role(어드민 백엔드 API 설계 2026-08-01 §1)은 세션 스냅샷이 아니라 이 record를 통해 매 요청 DB
 * 최신값을 읽는다 — /v1/me의 role 필드가 강등·승격을 즉시 반영한다.
 * featureOverrides는 jsonb 원문(::text) 그대로 담는다 — 파싱은 FeatureOverridesCodec 몫이고,
 * role과 마찬가지로 매 요청 DB에서 읽으므로 어드민이 바꾼 값이 세션 갱신 없이 즉시 반영된다.
 */
public record UserProfile(long id, String email, String name, String nickname, String userType,
		String signupRoute, String phoneCountryCode, String phoneNumber, String companyName,
		String companySize, String industry, String jobTitle, boolean agreedMarketing,
		OffsetDateTime marketingUpdatedAt, String profileImageUrl, OffsetDateTime createdAt, String role,
		String featureOverrides) {
}
