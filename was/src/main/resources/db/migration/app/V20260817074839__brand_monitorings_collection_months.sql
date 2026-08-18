-- 유저별 표시 기간(2026-08-17 스펙) — 등록 시 신청한 수집 범위를 연결(관계) 레벨에 저장한다.
-- 크롤 자산(monitoring brand_account.collection_months, 유저 간 max)과 별개의 표시 창이다.
-- 기존 행은 12(현행 표시 그대로 — 신청값이 영속화된 적이 없어 복원 불가, 스펙 §결정 요약).
ALTER TABLE app.brand_monitorings
    ADD COLUMN collection_months int NOT NULL DEFAULT 12
    CHECK (collection_months IN (1, 3, 6, 12));
