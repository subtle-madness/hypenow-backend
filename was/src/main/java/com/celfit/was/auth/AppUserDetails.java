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
 * email 필드는 **직렬화 호환용 잔존**이다(트랙 A, 09-03 — 세션 principal에서 이메일 PII 제거) —
 * 필드 선언은 그대로 두되 값은 항상 null로 저장한다(형상 불변이 재로그인 없는 전환의 근거,
 * AppUserDetailsPrincipalTest 참조). getUsername()도 이메일이 아니라 userId 문자열을 반환한다
 * (principal_name 매칭 의미는 유지 — 신규 세션은 userId로 색인된다. 기존 email-principal 세션의
 * 전환기 한계는 V1MeController 주석 참조).
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
		this.email = null; // 트랙 A(09-03) — 세션 principal에 이메일을 싣지 않는다, 필드는 직렬화 호환용
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
		// 트랙 A(09-03) — 세션 principal에서 이메일 제거, userId 문자열로 매칭(principal_name 인덱스도 동일)
		return String.valueOf(userId);
	}

	@Override
	public void eraseCredentials() {
		this.password = null;
	}
}
