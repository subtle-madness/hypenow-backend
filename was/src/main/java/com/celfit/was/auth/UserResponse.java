package com.celfit.was.auth;

public record UserResponse(long id, String email) {

	public static UserResponse from(AppUser user) {
		return new UserResponse(user.id(), user.email());
	}

	public static UserResponse from(AppUserDetails principal) {
		return new UserResponse(principal.getUserId(), principal.getUsername());
	}
}
