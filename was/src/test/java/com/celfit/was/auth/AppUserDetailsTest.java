package com.celfit.was.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

/**
 * 권한 발급 + 직렬화 계약 — role은 transient라 직렬화 왕복 후 principal 권한은 비어 있어야 한다
 * (세션의 실제 권한 스냅샷은 Authentication 토큰 소관 — AppUserDetails javadoc 참조).
 */
class AppUserDetailsTest {

	private static AppUserDetails details(String role) {
		return new AppUserDetails(
				new AppUser(1L, "a@b.c", "hash", role, OffsetDateTime.parse("2026-07-19T00:00:00Z")));
	}

	@Test
	void ADMIN이면_ROLE_ADMIN_권한을_발급한다() {
		assertThat(details("ADMIN").getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_ADMIN");
	}

	@Test
	void USER면_ROLE_USER_권한을_발급한다() {
		assertThat(details("USER").getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_USER");
	}

	@Test
	void 직렬화_왕복_후_principal_권한은_비어_있다() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(details("ADMIN"));
		}
		AppUserDetails restored;
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			restored = (AppUserDetails) in.readObject();
		}
		assertThat(restored.getAuthorities()).isEmpty();
		assertThat(restored.getUserId()).isEqualTo(1L); // 안정 필드는 보존
	}
}
