-- 태그 부재 검증 스로틀(2026-08-25 tagged 삭제 감지 설계 §3) — 커버된 열거에서 사라진 tagged
-- 게시물을 단건 검증 콜로 확인한 시각. 살아있는데 태그만 해제된 게시물이 매일 검증 콜(과금)을
-- 유발하지 않게 7일 스로틀의 기준이 된다. 재관측(touchCrawled)이 NULL로 되돌린다.
ALTER TABLE brand_tagged_post ADD COLUMN absence_checked_at timestamptz;
