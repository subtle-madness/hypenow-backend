-- 계정 카피 배치 전송(2026-08-17 계획) 상태 테이블 — content_batch_jobs(V20260811021726) 동형.
-- timely 컬럼은 계정 카피에 없음(콘텐츠 전용 개념). 사이드카는 DB 컬럼 보관(컨테이너에 쓰기 볼륨 없음).
CREATE TABLE account_batch_jobs (
    id              bigserial PRIMARY KEY,
    batch_name      text NOT NULL,
    submitted_count int NOT NULL,
    status          text NOT NULL DEFAULT 'pending',
    submitted_at    timestamptz NOT NULL DEFAULT now(),
    collected_at    timestamptz,
    note            text,
    sidecar_jsonl   text,
    CONSTRAINT account_batch_jobs_status_check CHECK (status IN ('pending', 'collected', 'failed'))
);
CREATE INDEX account_batch_jobs_status_idx ON account_batch_jobs (status);
