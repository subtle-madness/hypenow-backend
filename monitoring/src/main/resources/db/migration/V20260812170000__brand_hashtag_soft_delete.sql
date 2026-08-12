-- 태그 유저 관리 도입(2026-08-12) — 삭제는 tombstone: 행을 지우면 등록 replay의 자동 시드
-- (insertTags ON CONFLICT DO NOTHING)가 같은 태그를 되살린다. deleted_at 행이 남아 있으면
-- 시드가 충돌로 무시돼 유저의 삭제 의사가 보존된다.
ALTER TABLE brand_hashtag ADD COLUMN deleted_at timestamptz;
