package com.celfit.was.crypto;

/** DEK 번들 — AES 데이터 키 + HMAC 블라인드 인덱스 키(용도 분리, 스펙 §키 계층). 래핑 해제 결과로만 생성된다. */
public record DekBundle(byte[] aesKey, byte[] hmacKey) {

	public static DekBundle fromBytes(byte[] raw) {
		if (raw == null || raw.length != 64) {
			throw new IllegalArgumentException("DEK 번들은 64바이트(AES 32 + HMAC 32)여야 한다");
		}
		byte[] aes = new byte[32];
		byte[] hmac = new byte[32];
		System.arraycopy(raw, 0, aes, 0, 32);
		System.arraycopy(raw, 32, hmac, 0, 32);
		return new DekBundle(aes, hmac);
	}
}
