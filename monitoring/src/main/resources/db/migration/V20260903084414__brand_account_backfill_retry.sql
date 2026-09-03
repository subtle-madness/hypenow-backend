-- 등록 백필 열거 실패 재시도 스케줄러(2026-09-03, 결함 1) — 재시도 예산을 DB 컬럼으로 영속화한다.
-- monitoring은 롤링이 아니라 재기동 다운타임 배포다(deploy/README.md §5-1) — 메모리 카운터는
-- 배포·재기동마다 0으로 리셋돼, 벤더 장애(08-27 Hiker 503 전례) 중 재배포가 장애 중인 벤더에
-- 재시도를 다시 상한만큼 쏟아붓는 증폭 구조가 된다. expand 단계(ADD COLUMN 2개, DROP/RENAME
-- 없음) — was는 이 두 컬럼을 SELECT하지 않으므로 배포 순서 결합이 없다.
ALTER TABLE brand_account
    ADD COLUMN IF NOT EXISTS backfill_attempts     int NOT NULL DEFAULT 0,   -- 복구 스케줄러 재시도 횟수(등록 첫 시도는 0)
    ADD COLUMN IF NOT EXISTS backfill_attempted_at timestamptz;              -- 마지막 재시도 제출 시각(백오프 기준)
