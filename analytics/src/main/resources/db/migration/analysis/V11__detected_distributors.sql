-- B3 VLM 잔여분 (2026-07-14): 유통사 감지 저장 + 대분류 어휘 고정.
-- 어휘는 celfit-front 배포본 필터와 1:1 (분석 층이 확정, was는 verbatim 전달 — ARCHITECTURE §4-4).
-- 배열은 jsonb 컨벤션. 유통사 어휘(올리브영/다이소)는 Java sanitize가 지킨다(BeautyTaxonomy).
ALTER TABLE content_analyses
    ADD COLUMN detected_distributors jsonb;

-- main_category는 지금까지 전량 NULL(VLM 게이트 off)이라 CHECK 추가가 안전하다.
ALTER TABLE content_analyses
    ADD CONSTRAINT content_analyses_main_category_check
    CHECK (main_category IN ('skincare', 'suncare', 'makeup', 'cleansing', 'haircare', 'fragrance'));
