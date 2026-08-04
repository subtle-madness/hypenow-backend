-- 좋아요 숨김 관측 플래그 — 게시자가 like_and_view_counts_disabled를 켜면 like_count가
-- 실측이 아니라 프리뷰 잔여값으로 잘려 와서 likes를 null로 저장하는데(08-03 파서 수정),
-- FE가 "숨김"과 "그날 수집 실패(행 부재)"를 구분해 표시하려면 null만으로는 부족하다.
ALTER TABLE post_snapshot ADD COLUMN likes_hidden boolean NOT NULL DEFAULT false;

-- 백필: 기존 행에서 likes가 null인 경우는 숨김 관측을 수동 보정한 행뿐이다
-- (파서 수정 전에는 like_count가 전 응답에 실려 와 null이 생길 경로가 없었다).
UPDATE post_snapshot SET likes_hidden = true WHERE likes IS NULL;
