-- 분석 파이프라인 app_setting 기준값 시드 (2026-07-20).
-- 배경: 수동 INSERT로만 관리되던 분석 키들이 postgres-raw 컨테이너 이전(07-19) 때 유실돼
-- 새벽 분석 잡이 코드 기본값(상한 10)으로 돌던 사고의 재발 방지 — 기준값은 마이그레이션으로
-- 관리해 변경 이력을 남긴다(app_setting 테이블 자체가 crawler Flyway 소관이라 여기 둔다).
-- ON CONFLICT DO NOTHING: 운영 런타임 오버라이드(임시 상향·프로바이더 전환 등)는 보존 —
-- 이 시드는 "없으면 채우는 기준값"이지 런타임 값을 되돌리는 장치가 아니다.
INSERT INTO app_setting(key, value) VALUES
  ('analytics.analyze-batch-limit', '450'),          -- 콘텐츠 분석 1회 상한 (07-19 운영 확정값)
  ('analytics.account-analyze-batch-limit', '150'),  -- 계정 카피 1회 상한 (07-19 운영 확정값)
  ('analytics.analyze-timely-slack-days', '1'),      -- 제때 크롤 여유(일) — 뷰·Java 공용 판정 기준
  ('analytics.recent-window', '12'),                 -- 최근 N개 윈도우 — 01 뷰·분석 자격 공용
  ('analytics.llm-provider', 'gemini'),              -- gemini | vertex | anthropic — 전환은 UPDATE+재기동
  ('analytics.vertex-project', 'hypenow-llm-prod'),  -- Vertex GCP 프로젝트 (07-20 개설)
  ('analytics.vertex-gcs-bucket', 'hypenow-llm-batch') -- Vertex 배치 입출력 버킷
ON CONFLICT (key) DO NOTHING;
