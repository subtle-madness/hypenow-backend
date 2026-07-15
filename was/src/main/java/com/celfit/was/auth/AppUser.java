package com.celfit.was.auth;

import java.time.OffsetDateTime;

/** app.users 1행 — 서비스 데이터(§4-4), 분석 결과와 무관한 was 로컬 record. */
public record AppUser(long id, String email, String passwordHash, OffsetDateTime createdAt) {
}
