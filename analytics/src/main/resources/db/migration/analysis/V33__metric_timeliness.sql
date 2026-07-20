-- 분석 지표 시점 마킹 (07-19) — 제때 크롤 가드(PR #49) 도입 전 기분석분과 이후 유입을 구분한다.
--   timely        : 고정 지표가 업로드 +pin~pin+slack일 안에 잡힌 제때 크롤분 (가드 통과 — 데일리 잡 유입)
--   late_backfill : 늦크롤 백필 — +pin+slack일 이후 지표 (MVP 분석 제외 대상, 유료 Batch 백필 유입)
--   immature      : +pin일 미만 폴백 지표로 영구 고정된 누수 (가드 도입 전 유입만 존재)
-- 기존 행은 NULL로 두고 운영 1회성 UPDATE(raw v_contents 대조)로 채운다 — analysis DB는 raw를 못 본다.
-- 서빙(랭킹) 노출 정책은 미결 — 이 컬럼은 구분 표기·후속 결정용 마킹만 담당한다.
ALTER TABLE content_analyses ADD COLUMN metric_timeliness text
    CHECK (metric_timeliness IN ('timely', 'late_backfill', 'immature'));
