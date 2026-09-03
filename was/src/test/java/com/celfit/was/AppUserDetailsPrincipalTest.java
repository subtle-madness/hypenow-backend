package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 세션 principal 이메일 제거(Task 9, 트랙 A) — AppUserDetails.getUsername()이 이제 이메일이 아니라
 * userId 문자열을 반환한다. 직렬화 형상(필드 선언·serialVersionUID)은 손대지 않았다는 것이 곧
 * "전원 재로그인 없음"의 근거이므로, 이 테스트의 핵심은 ②의 구버전 픽스처 역직렬화 성공이다.
 *
 * 픽스처(src/test/resources/fixtures/app-user-details-legacy.ser, 클래스패스 루트 기준
 * /fixtures/app-user-details-legacy.ser로 읽는다 — 작업 디렉토리 상대 경로는 IDE·Gradle·서로
 * 다른 cwd에서 깨질 수 있어서 회피)는 이 태스크 착수 시점,
 * AppUserDetails가 아직 getUsername()으로 이메일을 반환하던 **변경 전** 클래스로 생성했다
 * (userId=42, email="legacy-fixture@example.test" — 테스트 전용 가짜 값, 실사용자 데이터 아님).
 * 재생성 절차: AppUserDetails.getUsername()을 `return email;`로 되돌린 상태에서
 * `new AppUserDetails(new AppUser(42L, "legacy-fixture@example.test", "hash", "USER",
 * OffsetDateTime.parse("2026-07-19T00:00:00Z")))`를 ObjectOutputStream으로 직렬화해 위 경로에 저장.
 */
class AppUserDetailsPrincipalTest {

	private static final String LEGACY_FIXTURE = "/fixtures/app-user-details-legacy.ser";

	private static AppUserDetails newDetails() {
		return new AppUserDetails(new AppUser(42L, "legacy-fixture@example.test", "hash", "USER",
				OffsetDateTime.parse("2026-07-19T00:00:00Z")));
	}

	@Test
	void getUsername은_이메일이_아니라_userId_문자열이다() {
		assertThat(newDetails().getUsername()).isEqualTo("42");
	}

	@Test
	void 구버전_직렬화_픽스처를_새_클래스로_역직렬화해도_userId가_보존된다() throws Exception {
		AppUserDetails restored;
		try (InputStream resource = getClass().getResourceAsStream(LEGACY_FIXTURE)) {
			if (resource == null) {
				throw new AssertionError("클래스패스에서 픽스처를 못 찾음: " + LEGACY_FIXTURE);
			}
			try (ObjectInputStream in = new ObjectInputStream(resource)) {
				restored = (AppUserDetails) in.readObject();
			}
		}

		// 형상(필드 선언·serialVersionUID)이 그대로라 역직렬화 자체가 성공한다 —
		// 기존 세션(SPRING_SECURITY_CONTEXT)이 재로그인 없이 계속 유효하다는 직접 증거.
		assertThat(restored.getUserId()).isEqualTo(42L);
	}

	@Test
	void 새로_직렬화한_바이트에는_이메일_문자열이_없다() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(newDetails());
		}

		String raw = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
		assertThat(raw).doesNotContain("@");
	}
}
