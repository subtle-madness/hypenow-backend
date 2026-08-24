-- 인플루언서 F&B 축 판정 컬럼 (스펙 2026-08-23 §1) — expand만, 파괴 없음.
-- beauty 축 컬럼 세트와 대칭. NULL = F&B 축 미판정(백필 대상).
ALTER TABLE influencer
  ADD COLUMN fnb boolean,
  ADD COLUMN fnb_company boolean,
  ADD COLUMN fnb_class text,
  ADD COLUMN fnb_source text,
  ADD COLUMN fnb_reason text,
  ADD COLUMN fnb_basis text,
  ADD COLUMN fnb_judged_at timestamptz,
  ADD COLUMN fnb_caption_count smallint;

-- F&B 수집·시드 파이프라인 게이트 — 기본 off (스펙 §4). 판정 모수·비용 확인 후 수동 UPDATE로 on.
-- ON CONFLICT DO NOTHING: 런타임 오버라이드 보존 (V16 관용구).
INSERT INTO app_setting(key, value) VALUES ('fnb.pipeline-enabled', 'false')
ON CONFLICT (key) DO NOTHING;
