-- 게시물별 보강 정산 완료 시각(2026-08-13 완결 배치 서빙 스펙 §1).
-- 의미는 "보강 시도가 끝났다"이지 "게시자·댓글이 다 찼다"가 아니다 — 게시자 404(실측 2%)·
-- 타임아웃(1%)으로 값이 비어도 정산한다. 빈 값은 게시자 stale·댓글 워터마크가 다음 스윕에서 채운다.
ALTER TABLE brand_tagged_post ADD COLUMN enriched_at timestamptz;

-- 기존 행 백필은 필수다 — 빠뜨리면 was 게이트(enriched_at IS NOT NULL) 도입 순간
-- 전 브랜드의 게시물 목록이 통째로 빈다. 이미 보강이 돌았던 행들이라 정산 완료로 보는 것이
-- 사실과 맞고, last_crawled_at(마지막으로 열거에서 만난 시각)이 그 근사치다.
UPDATE brand_tagged_post SET enriched_at = COALESCE(last_crawled_at, first_seen_at);
