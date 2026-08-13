-- 브랜드 수집 범위 선택(collectionMonths 스펙 2026-08-12) — 자산 레벨 수집 창 + 확장 폴링 앵커.
-- 기존 행은 전부 12개월 수집이었으므로 DEFAULT 12가 사실과 일치하는 백필이다(FE 요청서 §3).
ALTER TABLE brand_account ADD COLUMN collection_months int NOT NULL DEFAULT 12;
ALTER TABLE brand_account ADD CONSTRAINT brand_account_collection_months_chk
    CHECK (collection_months IN (1, 3, 6, 12));
-- FE 수집 폴링(30분 상한)의 앵커 — 확장 시작 시 now()로 갱신된다. nullable 유지(expand-contract),
-- 읽기는 COALESCE(collection_started_at, registered_at)로 접는다.
ALTER TABLE brand_account ADD COLUMN collection_started_at timestamptz;
UPDATE brand_account SET collection_started_at = registered_at;
