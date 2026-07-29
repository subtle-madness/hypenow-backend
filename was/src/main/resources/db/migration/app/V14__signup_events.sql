-- 가입 시도 추적 이벤트(2026-07-29) — 가입 완료 API의 시도·실패가 로그 어디에도 안 남던 갭을 메운다.
-- 4xx 무로깅 설계(V1ExceptionAdvice)는 유지하고, POST /v1/auth/signup 요청 1건당 1행을 영속화한다.
CREATE TABLE app.signup_events (
    id         bigserial PRIMARY KEY,
    email      text NOT NULL,                   -- lower 정규화. 가입 전 단계라 users 참조 없음(형식 위반 입력도 그대로 보존)
    outcome    text NOT NULL,                   -- ok | v1 에러 코드(INVALID_SIGNUP_CODE, VALIDATION_FAILED, ...) | INTERNAL_ERROR
    ip         text,                            -- 동일인 재시도 구분용
    detail     jsonb,                           -- progress(통과한 내부 단계 순서)·signupCode·message(실패 사유)·userId(성공 시)
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX signup_events_ix1 ON app.signup_events (email, created_at);
CREATE INDEX signup_events_ix2 ON app.signup_events (outcome, created_at);
