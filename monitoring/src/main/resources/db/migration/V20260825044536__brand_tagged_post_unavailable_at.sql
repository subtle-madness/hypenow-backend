-- 브랜드 direct 게시물 삭제·비공개 관측(2026-08-25 설계) — 야간 스윕 단건 콜의 404를 영속화한다.
-- NULL = 정상, 값 = 마지막 단건 조회가 404를 받은 시각. 재관측(touchCrawled)이 NULL로 되돌린다.
-- tagged-only 행은 단건 콜 자체가 없어 항상 NULL이다(감지 대상 외 — 설계 §범위).
ALTER TABLE brand_tagged_post ADD COLUMN unavailable_at timestamptz;
