package com.celfit.was.v1.account;

import com.celfit.was.auth.UserProfile;

/** 가입·로그인 응답 사용자 요약(스펙 6.15) — id는 문자열(프론트 계약). */
public record UserSummary(String id, String email, String name, String userType) {

	public static UserSummary from(UserProfile profile) {
		return new UserSummary(String.valueOf(profile.id()), profile.email(), profile.name(), profile.userType());
	}
}
