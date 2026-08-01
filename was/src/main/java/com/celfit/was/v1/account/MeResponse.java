package com.celfit.was.v1.account;

import com.celfit.was.auth.UserProfile;
import java.time.OffsetDateTime;

/**
 * GET/PATCH /v1/me 응답(스펙 6.12) — id는 문자열(프론트 계약), 시각은 ISO Z(nullable은 null 그대로).
 * role(어드민 백엔드 API 설계 2026-08-01 §1)은 DB의 USER/ADMIN을 소문자(user/admin)로 매핑한 신규
 * 필드 — 기존 필드·포맷은 운영 프론트가 소비 중이라 절대 변경하지 않는다.
 */
public record MeResponse(String id, String email, String name, String nickname, String userType,
		String jobTitle, String phoneCountryCode, String phoneNumber, String companyName,
		String companySize, String industry, String signupRoute, boolean agreedMarketing,
		String marketingUpdatedAt, String profileImageUrl, String createdAt, String role) {

	public static MeResponse from(UserProfile profile) {
		return new MeResponse(String.valueOf(profile.id()), profile.email(), profile.name(),
				profile.nickname(), profile.userType(), profile.jobTitle(), profile.phoneCountryCode(),
				profile.phoneNumber(), profile.companyName(), profile.companySize(), profile.industry(),
				profile.signupRoute(), profile.agreedMarketing(), isoZ(profile.marketingUpdatedAt()),
				profile.profileImageUrl(), isoZ(profile.createdAt()), toLowerRole(profile.role()));
	}

	private static String toLowerRole(String role) {
		return role == null ? null : role.toLowerCase();
	}

	private static String isoZ(OffsetDateTime time) {
		return time == null ? null : time.toInstant().toString();
	}
}
