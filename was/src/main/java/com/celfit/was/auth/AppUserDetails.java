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
 * role은 **transient** — 직렬화 형상 불변 조건을 지키면서 인증 시점 권한만 제공한다.
 * 세션에서 복원된 주체는 role=null이라 권한이 비어 있고, 스웨거 Basic 체인(STATELESS,
 * 매 요청 재인증)만 이 권한을 소비한다. 세션 기반 /v1 표면에서 role 검사를 하려면
 * 이 구조를 다시 설계할 것.
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
		// 세션 복원 주체는 role=null(transient) — 그때는 무권한
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
