package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.crypto.DekBundle;
import com.celfit.was.crypto.FieldCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** FieldCipher 단위 검증 — 라운드트립·IV 랜덤성·bidx 결정성·형식·오류 처리(스펙 §암호문·블라인드 인덱스). */
class FieldCipherTest {

	// 테스트 전용 고정 키(64바이트 = AES 32 + HMAC 32) — 운영 키와 무관
	private static final byte[] TEST_KEY = new byte[64];
	static {
		for (int i = 0; i < 64; i++) {
			TEST_KEY[i] = (byte) i;
		}
	}

	private final FieldCipher cipher = new FieldCipher(DekBundle.fromBytes(TEST_KEY), 1);

	@Test
	void 라운드트립_원문복원() {
		String token = cipher.encrypt("user@example.com");
		assertThat(cipher.decrypt(token)).isEqualTo("user@example.com");
	}

	@Test
	void 같은_평문도_IV가_랜덤이라_암호문이_다르다() {
		assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
	}

	@Test
	void 암호문은_버전_키ID_접두사를_가진다() {
		assertThat(cipher.encrypt("x")).startsWith("v1:1:");
	}

	@Test
	void 블라인드_인덱스는_결정적이고_역산불가_형식() {
		String a = cipher.blindIndex("user@example.com");
		assertThat(a).isEqualTo(cipher.blindIndex("user@example.com"));
		assertThat(a).isNotEqualTo(cipher.blindIndex("other@example.com"));
		assertThat(Base64.getUrlDecoder().decode(a)).hasSize(32); // SHA-256 출력
	}

	@Test
	void null은_그대로_통과() {
		assertThat(cipher.encrypt(null)).isNull();
		assertThat(cipher.decrypt(null)).isNull();
		assertThat(cipher.blindIndex(null)).isNull();
	}

	@Test
	void 손상된_암호문은_조용히_null이_아니라_예외() {
		String token = cipher.encrypt("x");
		String tampered = token.substring(0, token.length() - 4) + "AAAA";
		assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cipher.decrypt("garbage")).isInstanceOf(IllegalStateException.class);
	}
}
