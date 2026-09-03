-- 개인정보 봉투 암호화 expand 단계(트랙 A 스펙 2026-09-03) — 암호문(*_enc)·블라인드 인덱스(*_bidx)
-- 컬럼 추가와 래핑된 DEK 저장소. 전부 NULL 허용: 기존 행은 백필 커맨드(앱 레벨)가 채운다.
-- UNIQUE 인덱스는 백필 완료 후 후속 마이그레이션에서(부분 백필 상태 충돌 방지 — 스펙 §전환 1).
-- 평문 컬럼 DROP은 contract 단계(다음 릴리스) — expand-contract 규칙.

CREATE TABLE app.encryption_keys (
    key_id      smallint PRIMARY KEY,
    wrapped_dek bytea NOT NULL,          -- Vault KEK로 래핑된 DEK 번들(AES 32B + HMAC 32B) — 평문 키는 저장 금지
    created_at  timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE app.users
    ADD COLUMN email_enc        text,
    ADD COLUMN email_bidx       text,    -- HMAC(lower(email)) — 로그인 조회·UNIQUE(백필 후 생성)
    ADD COLUMN name_enc         text,
    ADD COLUMN nickname_enc     text,
    ADD COLUMN phone_number_enc text;

ALTER TABLE app.inquiries
    ADD COLUMN name_enc         text,
    ADD COLUMN email_enc        text,
    ADD COLUMN organization_enc text,
    ADD COLUMN message_enc      text;

ALTER TABLE app.password_resets
    ADD COLUMN email_enc  text,
    ADD COLUMN email_bidx text;          -- 조회 키 대체(백필 후 UNIQUE) — PK 교체는 contract에서

ALTER TABLE app.signup_events
    ADD COLUMN email_enc  text,
    ADD COLUMN email_bidx text,          -- 어뷰징 추적(email, created_at) 조회 대체
    ADD COLUMN ip_enc     text;
