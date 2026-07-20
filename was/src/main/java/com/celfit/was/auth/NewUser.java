package com.celfit.was.auth;

/**
 * 신규 사용자 프로필 입력(passwordHash 제외 순수 데이터) — UserRepository.insertProfile 파라미터.
 * v1의 SignupRequest를 직접 받지 않기 위한 경계 record(auth → v1 역방향 의존 차단),
 * 매핑은 v1측(SignupRequest.toNewUser())이 담당한다. 동의 3종은 검증 통과 후라 primitive boolean.
 * 선택 필드(signupRoute~jobTitle·usagePurpose)는 경량화(2026-07-19) 이후 null 허용.
 */
public record NewUser(String email, String name, String nickname, String userType, String signupRoute,
		String phoneCountryCode, String phoneNumber, String companyName, String companySize,
		String industry, String jobTitle, String usagePurpose, boolean agreedTerms, boolean agreedPrivacy,
		boolean agreedAge14, boolean agreedMarketing) {
}
