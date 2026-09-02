-- 태그별 스윕 실행 상태(FE 요청, 2026-08-31) — 태그 추가 후 "수집 중"과 "다 찾았는데 0건"을
-- 구분하지 못해 FE가 /posts meta.total 폴링 + 3분 타임아웃으로 우회하던 문제 해소.
-- 순수 additive라 expand-contract 문제 없음(신구 코드 공존 롤링 창에서도 구 코드는 새 컬럼을
-- 무시할 뿐이다).
ALTER TABLE brand_hashtag ADD COLUMN last_run_started_at  timestamptz;
ALTER TABLE brand_hashtag ADD COLUMN last_run_finished_at timestamptz;
ALTER TABLE brand_hashtag ADD COLUMN last_run_found_count integer;
ALTER TABLE brand_hashtag ADD COLUMN last_run_failed      boolean NOT NULL DEFAULT false;
