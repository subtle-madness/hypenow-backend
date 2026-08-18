-- 브랜드 direct 게시물 파이프라인 통합(2026-08-18 설계 §2-1·§3·§4-1) — expand 단계.

-- 브랜드 전용 등록 상태 저장소. 레거시 monitoring_registrations 위임을 끊는다 —
-- brand_id가 등록 행에 있어 share 해소분의 브랜드 추정(resolveLazyMappingBrand)이 통째로 불필요해진다.
CREATE TABLE app.brand_post_registrations (
    id           bigserial   PRIMARY KEY,
    user_id      bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    brand_id     bigint      NOT NULL,   -- monitoring brand_account.id 논리 참조(크로스 DB FK 금지)
    campaign_id  bigint      REFERENCES app.monitoring_campaigns(id) ON DELETE SET NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE INDEX brand_post_registrations_user_idx ON app.brand_post_registrations (user_id);

CREATE TABLE app.brand_post_registration_entries (
    registration_id bigint NOT NULL REFERENCES app.brand_post_registrations(id) ON DELETE CASCADE,
    seq             int    NOT NULL,
    input           text   NOT NULL,
    short_code      text,
    result          text   NOT NULL CHECK (result IN ('pending','success','failed','duplicate')),
    reason_code     text,
    reason          text,
    settled_at      timestamptz,
    PRIMARY KEY (registration_id, seq)
);

-- 게시물↔캠페인 N:M. 캠페인은 서비스 데이터라 monitoring이 아니라 여기 둔다(시스템 경계).
-- campaign_id에 CASCADE를 걸지 않는다 — ArchiveCascadeReachabilityTest가 monitoring_campaigns의
-- CASCADE 자식이 0개일 것을 강제한다(CampaignRepository.delete가 캠페인 1행만 아카이브·삭제한다는
-- 전제). 캠페인 삭제 경로에서 이 테이블을 명시적으로 아카이브·삭제한다
-- (brand_direct_posts가 monitoring_items에 대해 쓰는 패턴, V20260811090500과 동형).
CREATE TABLE app.brand_post_campaigns (
    brand_id    bigint      NOT NULL,
    short_code  text        NOT NULL,
    campaign_id bigint      NOT NULL REFERENCES app.monitoring_campaigns(id),
    user_id     bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, short_code, campaign_id)
);
CREATE INDEX brand_post_campaigns_campaign_idx ON app.brand_post_campaigns (campaign_id);
CREATE INDEX brand_post_campaigns_user_idx     ON app.brand_post_campaigns (user_id);

-- 이관 진행 표식. NOT NULL = "이 매핑의 정본은 monitoring 통합 풀이다"(레거시 조립 대상 아님).
-- 롤링 창과 이관 진행 중에도 카드가 사라지지 않게 하는 장치 — contract 단계에서 제거한다.
ALTER TABLE app.brand_direct_posts ADD COLUMN migrated_at timestamptz;

-- 신규 등록은 레거시 아이템을 만들지 않는다. DROP NOT NULL은 구버전 코드를 죽이지 않으므로
-- migration-guard의 금지 목록(SET NOT NULL)에 해당하지 않는다.
ALTER TABLE app.brand_direct_posts ALTER COLUMN monitoring_item_id DROP NOT NULL;
