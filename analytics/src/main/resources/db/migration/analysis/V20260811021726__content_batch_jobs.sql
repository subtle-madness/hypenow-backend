-- 콘텐츠 분석 배치 전송(Vertex 배치 API, 50% 할인) 제출 상태 테이블 (2026-08-11).
-- ANALYZE·LATE_BACKFILL_ANALYZE가 app_setting analytics.analyze-transport=batch일 때
-- 온라인 동기 호출 대신 배치로 제출하며 남기는 행 — BATCH_COLLECT 잡이 상태를 확인해 수거한다.
--
-- sidecar_jsonl: 제출 시 프롬프트에 실은 기준선 스냅샷(JSONL, short_code별 1행)을 DB에 직접
-- 보관한다. analytics 컨테이너에는 쓰기 가능한 볼륨이 없어(deploy/compose.yaml — secrets ro뿐)
-- 로컬 파일로 두면 제출~수거 사이에 배포·컨테이너 교체가 끼는 순간 유실되고, 유실되면 pending
-- 행이 영원히 pending으로 남아 수거 크론마다 에러 로그만 찍는 좀비가 된다(리뷰 지적, 08-11).
-- 한 배치 ~450행 × 수백 바이트 = 수백 KB 수준이라 text 컬럼으로 충분 — 수거 완료 시 NULL로
-- 비워 테이블 비대를 막는다(아래 status 전이 시 UPDATE 참조).
CREATE TABLE content_batch_jobs (
    id              bigserial PRIMARY KEY,
    batch_name      text NOT NULL,
    timely          boolean NOT NULL,
    submitted_count int NOT NULL,
    status          text NOT NULL DEFAULT 'pending',
    submitted_at    timestamptz NOT NULL DEFAULT now(),
    collected_at    timestamptz,
    note            text,
    sidecar_jsonl   text,
    CONSTRAINT content_batch_jobs_status_check CHECK (status IN ('pending', 'collected', 'failed'))
);

CREATE INDEX content_batch_jobs_status_idx ON content_batch_jobs (status);
