-- 지표 스냅샷 이력 미러 테이블 (ARCHITECTURE.md §4-3). 컬럼 이름·순서 = v_content_metric_snapshots = ContentMetricSnapshot.
-- id = raw_post_detail의 id (자연키 PK). FK 없음 — contents와는 short_code 논리 참조만 (TRUNCATE와 충돌 방지).
CREATE TABLE content_metric_snapshots (
    id          bigint PRIMARY KEY,
    short_code  text NOT NULL,
    captured_at timestamptz NOT NULL,
    views       bigint,
    likes       bigint,
    comments    bigint,
    hype_score  bigint
);
-- as-of 조회 경로: 게시물별 시점 범위 스캔
CREATE INDEX idx_content_metric_snapshots_code_captured ON content_metric_snapshots (short_code, captured_at);
