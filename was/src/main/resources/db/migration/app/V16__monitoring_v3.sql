-- 모니터링 v3(프론트 계약 6.25~6.33) 저장 계층 재구성.
-- 전제: 기능 미개통(MONITORING_ENABLED=off, 운영 0행) — 데이터 이관 없음.
-- allow-destructive: 개통 전 빈 테이블 재구성 — V13 매핑 테이블을 v3 추적 행으로 대체
DROP TABLE app.monitoring_campaigns;
-- no-backfill: 개통 전 빈 테이블 — 롤링 창 유실분 없음

-- v3 유저 캠페인(프론트 Campaign 6.25). 이름이 라우트 키 — (user, name) 유니크가 곧 계약.
CREATE TABLE app.monitoring_campaigns (
    id          bigserial PRIMARY KEY,
    user_id     bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    name        text   NOT NULL CHECK (char_length(name) <= 40),
    description text   CHECK (char_length(description) <= 200),
    start_date  date,
    end_date    date,
    brand       text   CHECK (char_length(brand) <= 30),
    budget      bigint CHECK (budget >= 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

-- 추적 행(프론트 TrackingItem) — V13 (user,target,멱등키) 매핑을 흡수·확장.
-- target_id NULL = 백그라운드 등록 처리 중(멱등키 replay 복구 가능 상태).
CREATE TABLE app.monitoring_items (
    id                  bigserial PRIMARY KEY,
    user_id             bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    mode                text   NOT NULL CHECK (mode IN ('url', 'account')),
    registration_key    uuid   NOT NULL UNIQUE,
    target_id           bigint,
    campaign_id         bigint REFERENCES app.monitoring_campaigns(id) ON DELETE SET NULL,
    input_value         text   NOT NULL,   -- url 모드: shortcode / account 모드: 소문자 핸들
    source_url          text,              -- url 모드만: 정규화된 등록 원본 URL
    keywords            jsonb,             -- account 모드만: {"and":[],"or":[],"exclude":[]}
    tracking_days       int    NOT NULL CHECK (tracking_days BETWEEN 1 AND 90),
    registered_on       date   NOT NULL,   -- KST 등록일 — 모든 기간 계산 기준
    canceled_at         timestamptz,
    canceled_from       text   CHECK (canceled_from IN ('detecting', 'tracking', 'error')),
    started_notified_on date,              -- collection_started 다이제스트 반영일(중복 발화 방지)
    created_at          timestamptz NOT NULL DEFAULT now()
);
-- (user_id, registered_on, id): 목록 조회 정렬 키(registered_on ASC, id ASC)를 인덱스가 그대로 커버.
CREATE INDEX monitoring_items_user_idx ON app.monitoring_items (user_id, registered_on, id);
CREATE UNIQUE INDEX monitoring_items_user_target_uidx
    ON app.monitoring_items (user_id, target_id) WHERE target_id IS NOT NULL;
-- 캠페인 삭제(ON DELETE SET NULL) 시 이 FK로 자식 행을 찾는데, 인덱스 없으면 매 삭제마다 전체 스캔.
CREATE INDEX monitoring_items_campaign_idx ON app.monitoring_items (campaign_id);

-- 등록 처리 내역(6.28) — 요청 1행 + 건별 결과(입력 순서 보존).
CREATE TABLE app.monitoring_registrations (
    id           bigserial PRIMARY KEY,
    user_id      bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE TABLE app.monitoring_registration_entries (
    registration_id bigint NOT NULL REFERENCES app.monitoring_registrations(id) ON DELETE CASCADE,
    seq             int    NOT NULL,
    input           text   NOT NULL,
    kind            text   NOT NULL CHECK (kind IN ('post', 'account')),
    result          text   NOT NULL CHECK (result IN ('success', 'failed', 'duplicate', 'pending')),
    reason_code     text   CHECK (reason_code IN ('invalid_format', 'not_found', 'private_account',
                                                  'share_link_unresolved', 'duplicate', 'internal_error')),
    reason          text,
    resolved_url    text,
    item_id         bigint REFERENCES app.monitoring_items(id) ON DELETE SET NULL,
    PRIMARY KEY (registration_id, seq)
);
CREATE INDEX monitoring_registrations_user_idx
    ON app.monitoring_registrations (user_id, requested_at DESC);
-- 아이템 삭제(ON DELETE SET NULL) 시 이 FK로 자식 행을 찾는데, 인덱스 없으면 매 삭제마다 전체 스캔.
CREATE INDEX monitoring_registration_entries_item_idx
    ON app.monitoring_registration_entries (item_id);

-- 데일리 다이제스트(6.32) — (user, date) 유니크가 하루 1건 계약.
CREATE TABLE app.monitoring_digests (
    id          bigserial PRIMARY KEY,
    user_id     bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    digest_date date   NOT NULL,
    items       jsonb  NOT NULL,   -- [{"category":"content","type":"...","summary":"...","count":N}]
    created_at  timestamptz NOT NULL DEFAULT now(),
    read_at     timestamptz,
    UNIQUE (user_id, digest_date)
);
CREATE INDEX monitoring_digests_user_idx ON app.monitoring_digests (user_id, digest_date DESC);

-- 옵트아웃 테이블은 V15가 생성 — 어휘 정본은 monitoring AlarmEventType(대문자), 6.33 소문자 매핑은 was 코드 몫.

-- 다이제스트 생성 워터마크(9시 크론 창의 시작점). 시드는 마이그레이션 시각 — 적용 이전 이벤트 일괄 발화 방지.
CREATE TABLE app.monitoring_alarm_state (
    event_type       text PRIMARY KEY,
    last_notified_at timestamptz NOT NULL
);
INSERT INTO app.monitoring_alarm_state (event_type, last_notified_at)
VALUES ('DIGEST', now())
ON CONFLICT DO NOTHING;
