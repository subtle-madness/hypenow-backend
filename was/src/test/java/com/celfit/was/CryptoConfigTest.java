package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.crypto.CryptoConfig;
import com.celfit.was.crypto.FieldCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * CryptoConfig — local 모드 빈 조립과 운영 fail-closed 가드(Task 11).
 * vault 모드의 실제 조립(DekStore·VaultDekWrapper)은 Vault 실통신이 필요해 여기선 검증하지
 * 않는다 — DekStore 자체의 부트스트랩·동시성 로직은 DekStoreTest(Testcontainers)가 담당.
 */
class CryptoConfigTest {

	private static final MockEnvironment NON_PROD = mockEnvironment("default");
	private static final MockEnvironment PROD = mockEnvironment("prod");

	private static MockEnvironment mockEnvironment(String... activeProfiles) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(activeProfiles);
		return env;
	}

	@Test
	void local_모드는_설정_키로_FieldCipher를_만든다() {
		byte[] key = new byte[64];
		String b64 = Base64.getEncoder().encodeToString(key);
		FieldCipher cipher = new CryptoConfig().fieldCipher("local", b64, null, null, 1, false, NON_PROD, null);
		assertThat(cipher.decrypt(cipher.encrypt("roundtrip"))).isEqualTo("roundtrip");
	}

	@Test
	void 알_수_없는_모드는_기동_실패() {
		assertThatThrownBy(
				() -> new CryptoConfig().fieldCipher("what", null, null, null, 1, false, NON_PROD, null))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void prod_프로파일에서_local_모드는_기동_실패() {
		byte[] key = new byte[64];
		String b64 = Base64.getEncoder().encodeToString(key);
		assertThatThrownBy(
				() -> new CryptoConfig().fieldCipher("local", b64, null, null, 1, false, PROD, null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("vault");
	}

	@Test
	void prod_프로파일이어도_allow_local_in_prod가_켜져있으면_local_모드가_통과한다() {
		byte[] key = new byte[64];
		String b64 = Base64.getEncoder().encodeToString(key);
		FieldCipher cipher = new CryptoConfig().fieldCipher("local", b64, null, null, 1, true, PROD, null);
		assertThat(cipher.decrypt(cipher.encrypt("roundtrip"))).isEqualTo("roundtrip");
	}

	@Test
	void prod가_아니면_local_모드가_그대로_통과한다() {
		byte[] key = new byte[64];
		String b64 = Base64.getEncoder().encodeToString(key);
		FieldCipher cipher = new CryptoConfig().fieldCipher("local", b64, null, null, 1, false, NON_PROD, null);
		assertThat(cipher).isNotNull();
	}
}
