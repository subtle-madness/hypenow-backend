package com.celfit.was.auth;

public record UserResponse(long id, String email) {

	public static UserResponse from(AppUser user) {
		return new UserResponse(user.id(), user.email());
	}

	// 트랙 A(09-03) — principal.getUsername()이 더 이상 이메일이 아니라(userId 문자열), 이메일은
	// 호출부가 DB에서 읽어 넘긴다.
	public static UserResponse from(AppUserDetails principal, String email) {
		return new UserResponse(principal.getUserId(), email);
	}
}
