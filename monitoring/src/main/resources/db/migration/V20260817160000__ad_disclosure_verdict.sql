-- 캡션 기반 광고 표기 판정(2026-08-17 스펙) — expand 단계, 전부 nullable ADD/신규 테이블.
-- brand_post_meta에 판정 6컬럼 + 시딩 계정 등록 테이블(brand_seeded_account) 신설.
-- 대상은 08-06 브랜드 전용 스키마만(기존 캠페인 테이블 무접촉, CLAUDE.md 시스템 경계).

ALTER TABLE brand_post_meta
    ADD COLUMN ad_verdict          text CHECK (ad_verdict IN
                                    ('DISCLOSED', 'NOT_DISCLOSED', 'INSUFFICIENT', 'UNCERTAIN')),
    ADD COLUMN ad_verdict_source   text CHECK (ad_verdict_source IN ('RULE', 'LLM')),
    ADD COLUMN ad_violations       jsonb,        -- 위반 코드 배열, 예: ["HIDDEN_PLACEMENT"]
    ADD COLUMN ad_evidence         jsonb,        -- 근거 문구 배열 [{phrase, category, offset}]
    ADD COLUMN ad_judged_at        timestamptz,
    ADD COLUMN judged_caption_hash text;         -- 판정 시점 caption의 MD5(애플리케이션 계산) — 캡션
                                                  -- 변경 재판정 트리거(스펙 §4). NULL = 미판정.

-- 브랜드가 등록한 시딩(협업) 인플루언서 계정(스펙 §6) — 판정 결과에는 저장하지 않는다.
-- 조회 시 (brand_id, author_username) 조인으로 계산해 목록을 나중에 등록·수정해도 재판정이 필요 없다.
CREATE TABLE brand_seeded_account (
    brand_id   bigint      NOT NULL REFERENCES brand_account (id),
    username   text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, username)
);
