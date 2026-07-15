package com.celfit.was.auth;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * app.users 1행 — 서비스 데이터(§4-4), 분석 결과와 무관한 was 로컬 record.
 * Serializable인 이유: 인증 주체(AppUserDetails)에 담겨 Spring Session JDBC가
 * SecurityContext를 JDK 직렬화로 app.spring_session_attributes에 저장한다.
 */
public record AppUser(long id, String email, String passwordHash, OffsetDateTime createdAt) implements Serializable {
}
