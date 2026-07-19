-- Swagger admin 게이트(설계 2026-07-19) — 최소 권한 체계. 기존 행은 전부 일반 사용자(USER)로 백필.
ALTER TABLE app.users
    ADD COLUMN role text NOT NULL DEFAULT 'USER'
        CHECK (role IN ('USER', 'ADMIN'));
