-- 콘텐츠 분석 배치 전송(Vertex 배치 API, 50% 할인) 제출 상태 테이블 (2026-08-11).
-- ANALYZE·LATE_BACKFILL_ANALYZE가 app_setting analytics.analyze-transport=batch일 때
-- 온라인 동기 호출 대신 배치로 제출하며 남기는 행 — BATCH_COLLECT 잡이 상태를 확인해 수거한다.
CREATE TABLE content_batch_jobs (
    id              bigserial PRIMARY KEY,
    batch_name      text NOT NULL,
    timely          boolean NOT NULL,
    submitted_count int NOT NULL,
    status          text NOT NULL DEFAULT 'pending',
    submitted_at    timestamptz NOT NULL DEFAULT now(),
    collected_at    timestamptz,
    note            text,
    CONSTRAINT content_batch_jobs_status_check CHECK (status IN ('pending', 'collected', 'failed'))
);

CREATE INDEX content_batch_jobs_status_idx ON content_batch_jobs (status);
