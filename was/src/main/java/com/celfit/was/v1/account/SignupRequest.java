package com.celfit.was.v1.account;

/**
 * v1 가입 요청(스펙 6.15) — 구 auth.SignupRequest(email/password만)와 별개.
 * 동의 3종(agreedTerms/Privacy/Age14)은 전부 true 필수, agreedMarketing만 선택.
 */
public record SignupRequest(String email, String password, String name, String nickname,
		String userType, String signupRoute, String phoneCountryCode, String phoneNumber,
		String companyName, String companySize, String industry, String jobTitle,
		Boolean agreedTerms, Boolean agreedPrivacy, Boolean agreedAge14, Boolean agreedMarketing) {

	/** null 방어 — 마케팅 동의는 명시적 true만 동의로 본다. */
	public boolean marketingAgreed() {
		return Boolean.TRUE.equals(agreedMarketing);
	}
}
