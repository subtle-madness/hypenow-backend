package com.celfit.was.auth;

import java.time.OffsetDateTime;

/**
 * app.users 1행 — 서비스 데이터(§4-4), 분석 결과와 무관한 was 로컬 record.
 * 세션에는 직렬화되지 않는다 — AppUserDetails가 안정 필드(userId, email)와 transient role만 복사해 간다.
 */
public record AppUser(long id, String email, String passwordHash, String role, OffsetDateTime createdAt) {
}
