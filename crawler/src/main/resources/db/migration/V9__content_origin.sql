-- V9__content_origin.sql
-- content에 origin 꼬리표 도입: 발굴 부산물(DISCOVERY, 보관용) vs 열거 산출(ENUMERATION, 수집 대상)
-- 기존 행은 전부 발굴 시대 데이터이므로 DISCOVERY로 채우고, 새 코드는 명시적으로 세팅한다
-- (default 유지 안 함 — 콜러가 항상 의미를 선택하게 강제).
ALTER TABLE content ADD COLUMN origin text NOT NULL DEFAULT 'DISCOVERY';
ALTER TABLE content ALTER COLUMN origin DROP DEFAULT;
