package com.celfit.was.auth;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 인증 주체 — **세션에 직렬화되는 형상이다(app.spring_session_attributes의 SPRING_SECURITY_CONTEXT).**
 * 필드 추가·변경 금지: 안정 식별자(userId, email)만 보유하고, 프로필 등 나머지는 매 요청 DB에서 읽는다.
 * (형상이 바뀌면 기존 세션 역직렬화가 깨져 전원 재로그인이 된다.)
 *
 * password는 인증 검증 중에만 쓰는 일시 필드 — CredentialsContainer 구현으로 인증 성공 직후
 * ProviderManager가 eraseCredentials()를 호출해 지우므로, BCrypt 해시가 세션 테이블에 실리지 않는다.
 * role은 **transient** — principal의 직렬화 형상을 안정 필드만으로 유지한다. 단, 세션에는
 * principal이 아니라 UsernamePasswordAuthenticationToken이 저장되고 그 authorities 필드는
 * non-transient라 **로그인 시점 권한 스냅샷은 세션에 남는다**(hasRole은 토큰 쪽을 읽는다).
 * 스웨거 체인이 STATELESS인 이유는 "세션 무권한"이 아니라 **신선도** — 매 요청 Basic 재인증이
 * 현재 DB role을 읽어 강등이 즉시 반영된다. 세션 기반 /v1 표면에서 role 검사를 하려면
 * 스냅샷 신선도(강등된 admin이 로그아웃 전까지 ROLE_ADMIN 유지) 문제를 포함해 재설계할 것.
 */
public class AppUserDetails implements UserDetails, CredentialsContainer {

	@Serial
	private static final long serialVersionUID = 1L;

	private final long userId;
	private final String email;
	private final transient String role;
	private String password;

	public AppUserDetails(AppUser user) {
		this.userId = user.id();
		this.email = user.email();
		this.role = user.role();
		this.password = user.passwordHash();
	}

	public long getUserId() {
		return userId;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		// 역직렬화된 principal은 role=null(transient) — 세션의 권한 스냅샷은 토큰(authorities) 쪽에 있다
		return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
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
