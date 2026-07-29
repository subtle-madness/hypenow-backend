package com.celfit.was.v1.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 캐시 키 축약 — 정규화된 쿼리를 SHA-256 hex로 접는다(같은 조건 = 같은 키, 스펙 §4).
 * 입력 정규화(기본값 명시화·순서 고정)는 호출자 책임 — 이 유틸은 임의 문자열 해시일 뿐이다.
 */
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

	/**
	 * 컴포넌트 → 단사(injective) 정규 문자열. null은 "-;", 스칼라는 "<길이>:<값>;", 리스트는
	 * "L<크기>;"+원소. 길이 접두라 자유입력이 구분자나 리터럴 "null"을 담아도 다른 튜플과 절대 겹치지
	 * 않는다. toString()을 그대로 쓰면 midCategory="null"과 midCategory=null이 같은 문자열이
	 * 된다(캐시 오염 — 2026-07-29 리뷰에서 실증).
	 */
	public static String canonical(Object... parts) {
		StringBuilder sb = new StringBuilder();
		for (Object p : parts) {
			if (p == null) {
				sb.append("-;");
			} else if (p instanceof List<?> list) {
				sb.append('L').append(list.size()).append(';').append(canonical(list.toArray()));
			} else {
				String v = p.toString();
				sb.append(v.length()).append(':').append(v).append(';');
			}
		}
		return sb.toString();
	}
}
