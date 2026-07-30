package com.celfit.was.monitoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 등록 요청(계약 v2.1 §2-1) — ACCOUNT/POST 공용이라 타입별 미사용 필드는 null이며 직렬화에서 뺀다.
 * (Jackson 3도 애노테이션 패키지는 com.fasterxml.jackson.annotation 유지)
 *
 * <p>{@code userId}는 v2.1부터 필수(누락 시 monitoring이 400 VALIDATION) — 알람 수신자 식별용
 * was 유저 id다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterRequest(String registrationKey, long userId, String type, String username,
		String shortCode, KeywordRule keywordRule, OffsetDateTime expiresAt) {

	public static RegisterRequest account(UUID key, long userId, String username, KeywordRule keywordRule,
			OffsetDateTime expiresAt) {
		return new RegisterRequest(key.toString(), userId, "ACCOUNT", username, null, keywordRule, expiresAt);
	}

	public static RegisterRequest post(UUID key, long userId, String shortCode, OffsetDateTime expiresAt) {
		return new RegisterRequest(key.toString(), userId, "POST", null, shortCode, null, expiresAt);
	}
}
