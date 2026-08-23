-- 수집 상한 v2(스펙 §7-1) — 백필 시점 창 커버리지 판정의 영속화.
-- covered_until NULL = 요청 창 전체 커버, capped=true면 covered_until이 실수집 깊이.
-- 기존 행은 전부 "컷 판정 이력 없음"이므로 DEFAULT false + NULL이 사실과 일치하는 백필이다
-- (컷 여부는 다음 백필·확장 시점에 기록된다 — 일일 스윕은 이 값을 건드리지 않는다).
ALTER TABLE brand_account
    ADD COLUMN collection_capped boolean NOT NULL DEFAULT false,
    ADD COLUMN covered_until timestamptz;
