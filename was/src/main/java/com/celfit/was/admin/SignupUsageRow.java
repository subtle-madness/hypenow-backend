package com.celfit.was.admin;

import java.time.OffsetDateTime;

/**
 * 관리자 가입 코드 사용 현황 한 행(설계 2026-07-19) — app.signup_codes LEFT JOIN app.users.
 * 미소진·탈퇴(FK ON DELETE SET NULL) 코드는 email·userId·usedAt이 null.
 * JdbcClient의 query(Class) 매핑 규약에 맞춰 SQL 별칭 user_id → userId, used_at → usedAt.
 */
public record SignupUsageRow(String code, String channel, String email, Long userId,
		OffsetDateTime usedAt) {
}
