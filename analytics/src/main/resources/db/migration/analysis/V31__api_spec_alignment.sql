-- API 스펙 정렬(2026-07-15 설계): 서빙 보강 3종.
-- ① contents.metric_captured_at — 지표 고정(+3일) 스냅샷의 수집 시각(스펙 updatedAt 재료)
-- ② accounts.external_link — raw_profile payload의 externalUrl(스펙 6.4, email은 미수집이라 필드 없음)
-- ③ beauty_distributors.slug — 유통사 필터 ID(스펙 6.1 distributorId). 어휘 생산자가 슬러그 확정(§4-4)
ALTER TABLE contents ADD COLUMN metric_captured_at timestamptz;
ALTER TABLE accounts ADD COLUMN external_link text;

ALTER TABLE beauty_distributors ADD COLUMN slug text;
UPDATE beauty_distributors SET slug = 'oliveyoung' WHERE name = '올리브영';
UPDATE beauty_distributors SET slug = 'daiso' WHERE name = '다이소';
ALTER TABLE beauty_distributors ALTER COLUMN slug SET NOT NULL;
ALTER TABLE beauty_distributors ADD CONSTRAINT beauty_distributors_slug_key UNIQUE (slug);
