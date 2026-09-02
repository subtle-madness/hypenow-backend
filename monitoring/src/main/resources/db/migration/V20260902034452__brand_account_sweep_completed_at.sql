-- 완주 시각 분리(2026-09-02) — 08-31 진행 워터마크 개정(touchProgress)으로 last_swept_at의 의미가
-- "완주 시각"에서 "마지막 수집 활동 시각"으로 넓어지면서, 완주 시각을 재는 소비자(Grafana 수집
-- 소요·신선도 패널)가 해시태그 스윕의 페이지 정산에 오염됐다(09-02 실측 — 야간 4.4h가 9h로 표시).
-- touchSwept(전량 수집 완주)만 찍는 전용 컬럼을 둬 워터마크와 계측을 분리한다.
ALTER TABLE brand_account ADD COLUMN IF NOT EXISTS sweep_completed_at timestamptz;

-- 초기값 백필 — 완주 이력이 있는 브랜드(last_swept_on 有)는 현재 last_swept_at이 최선의 근사다.
-- 워터마크 오염분이 섞일 수 있으나 다음 야간 스윕의 touchSwept가 자연 교정한다.
UPDATE brand_account SET sweep_completed_at = last_swept_at WHERE last_swept_on IS NOT NULL;
