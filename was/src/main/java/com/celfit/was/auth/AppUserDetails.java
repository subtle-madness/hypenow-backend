package com.celfit.was.auth;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 인증 주체 — **세션에 직렬화되는 형상이다(app.spring_session_attributes의 SPRING_SECURITY_CONTEXT).**
 * 필드 추가·변경 금지: 안정 식별자(userId, email)만 보유하고, 프로필 등 나머지는 매 요청 DB에서 읽는다.
 * (형상이 바뀌면 기존 세션 역직렬화가 깨져 전원 재로그인이 된다.)
 *
 * password는 인증 검증 중에만 쓰는 일시 필드 — CredentialsContainer 구현으로 인증 성공 직후
 * ProviderManager가 eraseCredentials()를 호출해 지우므로, BCrypt 해시가 세션 테이블에 실리지 않는다.
 * 권한 체계가 없어 authorities는 항상 비어 있다.
 */
public class AppUserDetails implements UserDetails, CredentialsContainer {

	@Serial
	private static final long serialVersionUID = 1L;

	private final long userId;
	private final String email;
	private String password;

	public AppUserDetails(AppUser user) {
		this.userId = user.id();
		this.email = user.email();
		this.password = user.passwordHash();
	}

	public long getUserId() {
		return userId;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of();
	}

	@Override
	public String getPassword() {
		return password;
	}

	@Override
	public String getUsername() {
		return email;
	}

	@Override
	public void eraseCredentials() {
		this.password = null;
	}
}
