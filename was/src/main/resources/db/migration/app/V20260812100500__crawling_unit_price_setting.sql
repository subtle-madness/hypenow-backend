-- 크롤링 전역 단가(USD/콜) 시드 — 어드민 크롤링 비용 카드(2026-08-12 프론트 요청서).
-- 유저별이 아니라 전역 1개. 초기값은 Hiker 계약 단가 실측치(콜당 $0.0006 — DECISIONS 08-06
-- 브랜드 태그 모니터링 비용 산정과 동일 값). 운영자가 PUT /v1/admin/crawling-cost/unit-price로
-- 즉시 조정한다(기준값 변경은 후속 마이그레이션으로).
INSERT INTO app.app_setting (key, value) VALUES ('crawling.unit-price-usd', '0.0006')
ON CONFLICT (key) DO NOTHING;
