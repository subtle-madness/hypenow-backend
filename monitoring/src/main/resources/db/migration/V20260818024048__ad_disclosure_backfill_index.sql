-- 미판정 잔여 백필 조회 가속(2026-08-18, 스펙 §7 개정) — 스윕 재열거 창(180일)을 벗어난
-- 게시물은 정기 스윕의 캡션 해시 재비교가 걸리지 않아 ad_verdict NULL이 영구 잔존할 수 있었다.
-- BrandSweepJob 백필 단계가 매일 밤 ad_verdict IS NULL 잔량을 findUnjudged로 스캔하는데,
-- 판정이 수렴할수록(잔량이 0에 가까워질수록) 이 부분 인덱스도 함께 작아져 조회가 사실상 공짜가 된다.
CREATE INDEX idx_brand_post_meta_unjudged ON brand_post_meta (short_code) WHERE ad_verdict IS NULL;
