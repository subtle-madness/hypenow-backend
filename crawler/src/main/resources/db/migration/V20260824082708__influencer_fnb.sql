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

-- 값 방어는 beauty 축 관용구를 그대로 따른다(V18·V21·V22) — fnb_class는 CategoryClass 5분류,
-- fnb_basis는 LLM이 밝힌 판정 주근거. CATEGORY_ONLY는 인스타그램 자기신고 category만 본 저확신 판정.
ALTER TABLE influencer ADD CONSTRAINT influencer_fnb_class_check
    CHECK (fnb_class IN ('INFLUENCER', 'COMPANY', 'SERVICE', 'FOREIGN_INFLUENCER', 'NONE'));
ALTER TABLE influencer ADD CONSTRAINT influencer_fnb_basis_check
    CHECK (fnb_basis IN ('CAPTION', 'BIO', 'CATEGORY_ONLY'));

-- F&B 수집·시드 파이프라인 게이트 — 기본 off (스펙 §4). 판정 모수·비용 확인 후 수동 UPDATE로 on.
-- ON CONFLICT DO NOTHING: 런타임 오버라이드 보존 (V16 관용구).
INSERT INTO app_setting(key, value) VALUES ('fnb.pipeline-enabled', 'false')
ON CONFLICT (key) DO NOTHING;
