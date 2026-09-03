-- 콘텐츠 분석 단계 분리 토글 기준값 (2026-09-03 설계 §4-6).
--   unified(기본) : 현행 통합 1콜. FACT_ANALYZE 잡은 no-op 로그만 남기고 끝난다.
--   split         : 파트 A(FACT_ANALYZE, 성숙 무관 D+1)와 파트 B(ANALYZE / LATE_BACKFILL_ANALYZE,
--                   성숙 후)로 분리. 잡 시작마다 읽으므로 재기동 없이 전환·롤백된다.
-- 기본값을 unified로 두는 이유: 배포 자체로는 운영 행동이 하나도 바뀌지 않게 하고, 골드셋 대조
-- (파트 A 정확도 회귀 확인) 후 UPDATE 한 줄로 켠다. 롤백도 같은 한 줄이다.
INSERT INTO app_setting (key, value)
VALUES ('analytics.analyze-mode', 'unified')
ON CONFLICT (key) DO NOTHING;
