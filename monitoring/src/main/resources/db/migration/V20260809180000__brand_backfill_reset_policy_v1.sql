-- 크롤링 정책 v1(2026-08-09) 소급 적용 — 기존 등록 브랜드도 12개월 백필을 받도록 last_swept_on을
-- 리셋한다(사용자 확정 08-09). null = 백필 미완이라 다음 스윕이 기존 경로 그대로 365일 전체를 연다.
-- 다음 스윕 완주 전까지 was가 "수집 준비 중"으로 표시할 수 있음(하루 이내) — 수용된 트레이드오프.
UPDATE brand_account SET last_swept_on = NULL WHERE status = 'ACTIVE';
