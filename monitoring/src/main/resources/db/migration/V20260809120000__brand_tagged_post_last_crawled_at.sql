-- 브랜드 크롤링 정책 v1(2026-08-09 스펙 §6) — 나이 기반 티어 판정의 게시물별 마지막 수집 시각.
-- null = 아직 티어 크롤 이전(기존 행·미완 수집분) → 판정식(BrandCrawlPolicy)이 무조건 due로
-- 취급해 첫 스윕에서 자연 수렴한다(백필 UPDATE 불필요). expand 단계 — 참조 코드와 같은 PR.
ALTER TABLE brand_tagged_post ADD COLUMN last_crawled_at timestamptz;
