-- 비밀번호 재설정(프론트 요청 2026-08-12) — 이메일당 1행이 2단계 상태를 순차로 담는다:
-- 코드 단계(code_hash·code_expires_at·attempts) → confirm 성공 시 코드 소모(code_hash NULL)
-- + 토큰 발급(token_hash·token_expires_at) → reset 성공 시 행 삭제(토큰 1회용).
-- 재발송 upsert는 코드 교체 + attempts·토큰 리셋(마지막 발송만 유효 — email_verifications V7 관용구).
CREATE TABLE app.password_resets (
    email            text PRIMARY KEY,
    code_hash        text,
    code_expires_at  timestamptz NOT NULL,
    attempts         int NOT NULL DEFAULT 0,
    token_hash       text,
    token_expires_at timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- reset(요청 3)은 토큰만 들고 온다 — 조회가 token_hash 기준이라 유니크 인덱스(NULL 다중 허용)
CREATE UNIQUE INDEX password_resets_token_hash_key ON app.password_resets (token_hash);
