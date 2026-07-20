package com.celfit.was.v1.account;

import com.celfit.was.auth.UserProfile;
import java.time.OffsetDateTime;

/** GET/PATCH /v1/me 응답(스펙 6.12) — id는 문자열(프론트 계약), 시각은 ISO Z(nullable은 null 그대로). */
public record MeResponse(String id, String email, String name, String nickname, String userType,
		String jobTitle, String phoneCountryCode, String phoneNumber, String companyName,
		String companySize, String industry, String signupRoute, boolean agreedMarketing,
		String marketingUpdatedAt, String profileImageUrl, String createdAt) {

	public static MeResponse from(UserProfile profile) {
		return new MeResponse(String.valueOf(profile.id()), profile.email(), profile.name(),
				profile.nickname(), profile.userType(), profile.jobTitle(), profile.phoneCountryCode(),
				profile.phoneNumber(), profile.companyName(), profile.companySize(), profile.industry(),
				profile.signupRoute(), profile.agreedMarketing(), isoZ(profile.marketingUpdatedAt()),
				profile.profileImageUrl(), isoZ(profile.createdAt()));
	}

	private static String isoZ(OffsetDateTime time) {
		return time == null ? null : time.toInstant().toString();
	}
}
