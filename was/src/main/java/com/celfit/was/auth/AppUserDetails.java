package com.celfit.was.auth;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** app.users 1행을 Spring Security의 인증 주체로 감싼다. 권한 체계가 없어 authorities는 항상 비어 있다. */
public class AppUserDetails implements UserDetails {

	private final AppUser user;

	public AppUserDetails(AppUser user) {
		this.user = user;
	}

	public long getUserId() {
		return user.id();
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of();
	}

	@Override
	public String getPassword() {
		return user.passwordHash();
	}

	@Override
	public String getUsername() {
		return user.email();
	}
}
