package com.celfit.was.monitoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 등록 요청(계약 §2-1) — ACCOUNT/POST 공용이라 타입별 미사용 필드는 null이며 직렬화에서 뺀다.
 * (Jackson 3도 애노테이션 패키지는 com.fasterxml.jackson.annotation 유지)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterRequest(String registrationKey, String type, String username,
		String shortCode, KeywordRule keywordRule, OffsetDateTime expiresAt) {

	public static RegisterRequest account(UUID key, String username, KeywordRule keywordRule,
			OffsetDateTime expiresAt) {
		return new RegisterRequest(key.toString(), "ACCOUNT", username, null, keywordRule, expiresAt);
	}

	public static RegisterRequest post(UUID key, String shortCode, OffsetDateTime expiresAt) {
		return new RegisterRequest(key.toString(), "POST", null, shortCode, null, expiresAt);
	}
}
