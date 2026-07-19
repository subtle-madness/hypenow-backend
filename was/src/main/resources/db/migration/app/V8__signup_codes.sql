-- 클로즈베타 배치 1회용 가입 코드(설계 2026-07-19) — 채널별 발급(THREADS-/DM-/LANDING- 접두사),
-- 가입 트랜잭션에서 원자 선점(UPDATE ... WHERE used_at IS NULL)으로 1회 소진.
-- 소진 판정은 used_at 기준 — used_by는 추적용이라 탈퇴(행 삭제) 시 NULL이 돼도 코드는 소진 상태를 유지한다.
-- 단일 공용 코드(app_setting 'signup.code')는 이 테이블로 즉시 전환 — 행이 없으면 가입 전면 차단(fail-closed 유지).
CREATE TABLE app.signup_codes (
    code       text PRIMARY KEY,
    channel    text NOT NULL,                    -- 발급 채널(THREADS/DM/LANDING 등) — 분석·추적용
    used_by    bigint REFERENCES app.users(id) ON DELETE SET NULL,  -- 소진한 사용자(미사용·탈퇴면 NULL)
    used_at    timestamptz,                      -- 소진 시각(미사용이면 NULL) — 소진 판정의 정본
    created_at timestamptz NOT NULL DEFAULT now()
);
