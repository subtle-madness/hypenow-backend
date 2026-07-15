package com.celfit.was.auth;

/**
 * app.users 프로필 요약 행 — v1 가입·로그인 응답(UserSummary) 조립용.
 * AppUserDetails는 안정 형상(userId, email)이라 프로필은 매번 DB에서 이 record로 읽는다.
 */
public record UserProfile(long id, String email, String name, String userType) {
}
