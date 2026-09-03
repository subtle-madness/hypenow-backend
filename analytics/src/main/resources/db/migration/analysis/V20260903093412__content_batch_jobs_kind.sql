-- 배치 제출 종류 구분 (2026-09-03 2단계 분리 설계 §4-5).
-- 수거 잡(ContentBatchCollectJob)이 응답 스키마를 알아야 파서를 고를 수 있다:
--   analyze   : 통합 1콜(레거시·롤백 경로) → ContentAnalysisWriter.insert
--   facts     : 파트 A            → ContentAnalysisWriter.insertFacts (metric_timeliness='pending')
--   synthesis : 파트 B            → ContentAnalysisWriter.updateSynthesis (사이드카 timely로 마킹)
-- DEFAULT 'analyze': 롤링 창·롤백 직후에 구 코드가 남긴 pending 행을 신 수거 잡이 통합 파서로
-- 처리한다(구 코드는 이 컬럼을 모르므로 INSERT 목록에 넣지 않는다).
-- timely 컬럼은 facts에서 의미가 없다 - false 고정으로 넣고 수거가 무시한다. NOT NULL 완화는
-- contract 단계 얘기라 하지 않는다.
ALTER TABLE content_batch_jobs
    ADD COLUMN kind text NOT NULL DEFAULT 'analyze';

ALTER TABLE content_batch_jobs
    ADD CONSTRAINT content_batch_jobs_kind_check
    CHECK (kind IN ('analyze', 'facts', 'synthesis'));
