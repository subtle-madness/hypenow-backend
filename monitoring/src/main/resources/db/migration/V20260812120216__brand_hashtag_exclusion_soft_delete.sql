-- 제외 문자열 유저 관리 확장(2026-08-12) — 태그와 같은 tombstone 필요: 등록 replay마다
-- insertDefaultExclusion(계정명 루트)이 돌아서, deleted_at 없이 하드 삭제하면 유저가 지운
-- 기본 제외 문자열이 시드로 부활한다(brand_hashtag의 V20260812111153과 같은 근거).
ALTER TABLE brand_hashtag_exclusion ADD COLUMN deleted_at timestamptz;
