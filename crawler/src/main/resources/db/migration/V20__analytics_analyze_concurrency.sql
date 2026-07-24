-- 콘텐츠 분석 동시 처리(병렬) 개수 기준값 시드 (2026-07-23).
-- 배경: LIMIT 폐지(2026-07-23, timely/late_backfill 분리) 이후에도 처리 루프가 순차라 대량
-- 백필 적체 시 처리에 수 시간이 걸리는 문제를 운영에서 확인(실측 26,167건 적체). Vertex는
-- RPM 페이싱이 없어(DSQ) 병렬화 여유가 있다.
-- ON CONFLICT DO NOTHING: 운영 런타임 오버라이드 보존 — 이 시드는 "없으면 채우는 기준값".
INSERT INTO app_setting(key, value) VALUES
  ('analytics.analyze-concurrency', '8')
ON CONFLICT (key) DO NOTHING;
