-- 삭제되는 행의 원본 보존(트랙 NN). 라이브 삭제 로직은 그대로 두고 삭제 직전 이관만 한다.
-- archive는 Flyway schemas 목록 밖이라 clean 대상에서 빠진다 — 운영에서 clean을 쓰지 않으므로 무해.
CREATE SCHEMA IF NOT EXISTS archive;

CREATE TABLE archive.archived_rows (
    id              bigserial PRIMARY KEY,
    -- 원본 테이블(스키마 한정). 예: 'app.saved_contents'
    table_name      text        NOT NULL,
    -- 복합 PK가 많아 단일 컬럼으로 못 담는다. 예: {"user_id":12,"short_code":"ABC"}
    row_pk          jsonb       NOT NULL,
    -- 조회 편의용 승격. entries처럼 user_id 컬럼이 없는 테이블은 NULL
    user_id         bigint,
    -- to_jsonb(원본 행) 전체. users만 직접 식별 컬럼을 뺀 가명화 형태로 넣는다
    payload         jsonb       NOT NULL,
    archived_at     timestamptz NOT NULL DEFAULT now(),
    archived_reason text        NOT NULL
        CHECK (archived_reason IN ('ACCOUNT_DELETION', 'SAVED_REMOVED', 'CAMPAIGN_DELETED', 'REGISTRATION_ROLLBACK'))
);

CREATE INDEX idx_archived_rows_table_time ON archive.archived_rows (table_name, archived_at);
CREATE INDEX idx_archived_rows_user ON archive.archived_rows (user_id) WHERE user_id IS NOT NULL;
