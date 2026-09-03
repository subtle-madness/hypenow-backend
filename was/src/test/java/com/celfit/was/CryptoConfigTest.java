package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.crypto.CryptoConfig;
import com.celfit.was.crypto.FieldCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** CryptoConfig local 모드 — Vault 없이 설정 키만으로 FieldCipher 빈이 만들어진다(테스트·로컬 개발 경로). */
class CryptoConfigTest {

	@Test
	void local_모드는_설정_키로_FieldCipher를_만든다() {
		byte[] key = new byte[64];
		String b64 = Base64.getEncoder().encodeToString(key);
		FieldCipher cipher = new CryptoConfig().fieldCipher("local", b64, null, null, 1, null);
		assertThat(cipher.decrypt(cipher.encrypt("roundtrip"))).isEqualTo("roundtrip");
	}

	@Test
	void 알_수_없는_모드는_기동_실패() {
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> new CryptoConfig().fieldCipher("what", null, null, null, 1, null))
				.isInstanceOf(IllegalStateException.class);
	}
}
