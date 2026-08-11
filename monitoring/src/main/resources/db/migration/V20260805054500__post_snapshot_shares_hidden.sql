-- 공유 숨김 관측 플래그(08-05) — 게시자가 "공유 횟수 숨기기"(share_count_disabled)를 켜거나
-- "좋아요 수 숨기기"(like_and_view_counts_disabled — IG 앱 문구대로 공유 노출도 함께 끈다)를
-- 켜면 reshare_count 키가 영구 부재해 shares가 null로 남는데, FE가 "비공개"와 "그날 수집
-- 실패(행 부재)"를 구분해 표시하려면 null만으로는 부족하다(likes_hidden과 동일 관용구).
ALTER TABLE post_snapshot ADD COLUMN shares_hidden boolean NOT NULL DEFAULT false;

-- 백필: 좋아요 숨김 행은 공유도 숨김이다(08-05 실측: lvcd=true 게시물 전원 공유 영구 부재).
-- share_count_disabled 단독 케이스(운영 1건)는 원형 재파싱 없이는 식별 불가 — 다음 스윕
-- upsert가 최신 행부터 채운다(과거 행은 false로 남는 수용된 한계).
UPDATE post_snapshot SET shares_hidden = true WHERE likes_hidden = true;
