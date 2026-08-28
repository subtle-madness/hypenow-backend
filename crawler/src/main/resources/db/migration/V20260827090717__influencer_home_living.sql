-- 인플루언서 홈/리빙 축 판정 컬럼 (스펙 2026-08-27) — expand만, 파괴 없음.
-- fnb 축 컬럼 세트(V20260824082708)와 대칭. NULL = 홈/리빙 축 미판정(백필 대상).
ALTER TABLE influencer
  ADD COLUMN home_living boolean,
  ADD COLUMN home_living_company boolean,
  ADD COLUMN home_living_class text,
  ADD COLUMN home_living_source text,
  ADD COLUMN home_living_reason text,
  ADD COLUMN home_living_basis text,
  ADD COLUMN home_living_judged_at timestamptz,
  ADD COLUMN home_living_caption_count smallint;

-- 값 방어는 fnb 축 관용구 그대로 — home_living_class는 CategoryClass 5분류,
-- home_living_basis는 LLM이 밝힌 판정 주근거(CATEGORY_ONLY는 자기신고 category만 본 저확신 판정).
ALTER TABLE influencer ADD CONSTRAINT influencer_home_living_class_check
    CHECK (home_living_class IN ('INFLUENCER', 'COMPANY', 'SERVICE', 'FOREIGN_INFLUENCER', 'NONE'));
ALTER TABLE influencer ADD CONSTRAINT influencer_home_living_basis_check
    CHECK (home_living_basis IN ('CAPTION', 'BIO', 'CATEGORY_ONLY'));

-- 홈/리빙 수집·시드 파이프라인 게이트 — 기본 off (스펙 §4). 판정 모수·비용 확인 후 수동 UPDATE로 on.
-- ON CONFLICT DO NOTHING: 런타임 오버라이드 보존 (V16 관용구).
INSERT INTO app_setting(key, value) VALUES ('home-living.pipeline-enabled', 'false')
ON CONFLICT (key) DO NOTHING;
