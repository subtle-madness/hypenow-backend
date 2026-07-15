package com.celfit.was.auth;

import java.time.OffsetDateTime;

/**
 * app.users 프로필 행(비밀 필드 제외) — v1 가입·로그인 응답(UserSummary)과 /v1/me(MeResponse) 조립용.
 * AppUserDetails는 안정 형상(userId, email)이라 프로필은 매 요청 DB에서 이 record로 읽는다.
 * 필드 순서는 NewUser와 동일 + 파생 필드(agreedMarketing 이후)가 뒤따른다.
 */
public record UserProfile(long id, String email, String name, String nickname, String userType,
		String signupRoute, String phoneCountryCode, String phoneNumber, String companyName,
		String companySize, String industry, String jobTitle, boolean agreedMarketing,
		OffsetDateTime marketingUpdatedAt, String profileImageUrl, OffsetDateTime createdAt) {
}
