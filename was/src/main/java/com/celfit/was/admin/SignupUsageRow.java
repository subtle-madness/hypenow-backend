package com.celfit.was.admin;

import java.time.OffsetDateTime;

/**
 * 관리자 가입 코드 사용 현황 한 행(설계 2026-07-19) — app.signup_codes LEFT JOIN app.users.
 * 세 가지 상태가 있다: ① 미소진 — email·userId·usedAt 모두 null. ② 소진+탈퇴(used_by가
 * ON DELETE SET NULL로 끊긴 경우) — email·userId는 null이지만 usedAt은 소진 시각 그대로 유지된다
 * (소진 판정의 정본은 used_at이지 used_by가 아니다 — V8 `signup_codes` 주석 참고). ③ 소진+생존 —
 * 셋 다 채워진다.
 * JdbcClient의 query(Class) 매핑 규약에 맞춰 SQL 별칭 user_id → userId, used_at → usedAt.
 */
public record SignupUsageRow(String code, String channel, String email, Long userId,
		OffsetDateTime usedAt) {
}
