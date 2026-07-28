package com.celfit.was.v1.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 캐시 키 축약 — 정규화된 쿼리 toString을 SHA-256 hex로 접는다(같은 조건 = 같은 키, 스펙 §4). */
public final class CacheKeys {

	private CacheKeys() {
	}

	public static String sha256(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 미지원 JVM", e);
		}
	}
}
