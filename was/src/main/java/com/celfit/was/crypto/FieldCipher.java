package com.celfit.was.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 필드 암호화 단일 정본(스펙 §암호문·블라인드 인덱스) — AES-256-GCM + HMAC-SHA256 블라인드 인덱스.
 * 암호문 형식 v1:<key_id>:<b64(iv 12B)>:<b64(ct+tag)> — 키 로테이션 시 신구 공존용 접두사.
 * 복호화 실패는 예외(조용한 데이터 소실 방지). 평문 키는 이 객체(메모리) 밖으로 내보내지 않는다.
 */
public class FieldCipher {

	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SecretKeySpec aesKey;
	private final SecretKeySpec hmacKey;
	private final String prefix;
	private final int keyId;

	public FieldCipher(DekBundle bundle, int keyId) {
		this.aesKey = new SecretKeySpec(bundle.aesKey(), "AES");
		this.hmacKey = new SecretKeySpec(bundle.hmacKey(), "HmacSHA256");
		this.keyId = keyId;
		this.prefix = "v1:" + keyId + ":";
	}

	public String encrypt(String plaintext) {
		if (plaintext == null) {
			return null;
		}
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
			byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			return prefix + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("필드 암호화 실패", e);
		}
	}

	public String decrypt(String token) {
		if (token == null) {
			return null;
		}
		String[] parts = token.split(":", 4);
		if (parts.length != 4 || !parts[0].equals("v1") || !parts[1].equals(String.valueOf(keyId))) {
			throw new IllegalStateException("알 수 없는 암호문 형식(접두사 불일치)");
		}
		try {
			byte[] iv = Base64.getDecoder().decode(parts[2]);
			byte[] ct = Base64.getDecoder().decode(parts[3]);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
			return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("필드 복호화 실패 — 손상된 암호문 또는 키 불일치", e);
		}
	}

	/** 등가 검색·UNIQUE용 지문 — 호출부가 정규화(이메일 lower 등)를 마친 값을 넘긴다(스펙: 정규화 규칙 재사용). */
	public String blindIndex(String normalized) {
		if (normalized == null) {
			return null;
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(hmacKey);
			return Base64.getUrlEncoder().withoutPadding()
					.encodeToString(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("블라인드 인덱스 생성 실패", e);
		}
	}
}
