package com.celfit.was.v1.account;

import com.celfit.was.auth.NewUser;

/**
 * v1 가입 요청(스펙 6.15 + 경량화 2026-07-19) — 구 auth.SignupRequest(email/password만)와 별개.
 * 동의 3종(agreedTerms/Privacy/Age14)은 전부 true 필수, agreedMarketing만 선택.
 * signupCode는 배치 1회용 코드(app.signup_codes) 대조 — 무효 시 403.
 * 선택 필드(signupRoute·phoneCountryCode·phoneNumber·companySize·industry·jobTitle·usagePurpose)는
 * null 허용. companyName은 필수 유지 — 유형별 소속명(브랜드명/유통사명/대행사명/계정명)으로 해석.
 */
public record SignupRequest(String signupCode, String email, String password, String name, String nickname,
		String userType, String signupRoute, String phoneCountryCode, String phoneNumber,
		String companyName, String companySize, String industry, String jobTitle, String usagePurpose,
		Boolean agreedTerms, Boolean agreedPrivacy, Boolean agreedAge14, Boolean agreedMarketing) {

	/** null 방어 — 마케팅 동의는 명시적 true만 동의로 본다. */
	public boolean marketingAgreed() {
		return Boolean.TRUE.equals(agreedMarketing);
	}

	/** auth 경계 record로 변환 — 매핑 책임은 v1측(auth가 v1 DTO를 모르게 하는 방향 유지). 자유 텍스트 선택 필드는 blank→null 정규화. */
	public NewUser toNewUser() {
		return new NewUser(email, name, nickname, userType, signupRoute, phoneCountryCode,
				blankToNull(phoneNumber), companyName, companySize, industry, jobTitle, blankToNull(usagePurpose),
				Boolean.TRUE.equals(agreedTerms), Boolean.TRUE.equals(agreedPrivacy),
				Boolean.TRUE.equals(agreedAge14), marketingAgreed());
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
