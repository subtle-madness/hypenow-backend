-- 이메일 소유권 인증(설계 2026-07-18) — 가입 전 강제. 이메일당 1행.
-- 재발송은 upsert(코드 재생성·attempts 리셋·verified_at 초기화 — 마지막 발송만 유효),
-- 가입 성공 직후 행 삭제(1회 소비). 주 방어선은 TTL 10분 + 오입력 5회 + 레이트리밋.
CREATE TABLE app.email_verifications (
    email           text PRIMARY KEY,
    code_hash       text NOT NULL,
    code_expires_at timestamptz NOT NULL,
    attempts        int NOT NULL DEFAULT 0,
    verified_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
