-- 브랜드 모니터링 연결(2026-08-07 스펙 §3-1) — expand 단계.
-- instgram 철자는 FE 명세 §10.1이 명시적으로 고정한 요청 컬럼명이다(오타 아님 — 계약).
ALTER TABLE app.users ADD COLUMN instgram_account_name varchar(30);
CREATE INDEX users_instgram_account_name_idx ON app.users (instgram_account_name);

-- user↔브랜드 활성 연결. brand_id는 monitoring brand_account.id 논리 참조(크로스 DB FK 금지).
CREATE TABLE app.brand_monitorings (
    id         bigserial PRIMARY KEY,
    user_id    bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    brand_id   bigint NOT NULL,
    username   text   NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);
-- 활성(미삭제) 연결은 유저당 하나 — 부분 유니크라 soft-delete 후 재등록은 열려 있다(§5.4 재가입).
CREATE UNIQUE INDEX brand_monitorings_active_user_uidx
    ON app.brand_monitorings (user_id) WHERE deleted_at IS NULL;
-- 브랜드 기준 잔여 활성 연결 카운트(마지막 연결 해제 시 monitoring 탈퇴 판정)용.
CREATE INDEX brand_monitorings_brand_idx ON app.brand_monitorings (brand_id);

-- 직접 등록 매핑 — 레거시 아이템에 "브랜드 화면 소속" 표식(FE brand_direct_media 역할).
CREATE TABLE app.brand_direct_posts (
    user_id            bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    brand_id           bigint NOT NULL,
    short_code         text   NOT NULL,
    monitoring_item_id bigint NOT NULL REFERENCES app.monitoring_items(id) ON DELETE CASCADE,
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, short_code)
);
