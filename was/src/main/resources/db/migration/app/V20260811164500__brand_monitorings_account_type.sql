-- 경쟁사 모니터링 계정 타입(2026-08-11 FE 요청) — 타입은 계정이 아니라 유저-계정 관계의 속성이다.
-- 같은 인스타 계정이 유저마다 다른 타입일 수 있어(담당 브랜드 vs 경쟁사) 구독 테이블에 둔다.
-- 기존 등록분은 전부 own이다 — 경쟁사 지정은 지금까지 브라우저 localStorage에만 있었으므로
-- 서버에 없는 게 맞고, 배포 후 유저가 경쟁사 화면에서 다시 지정한다(FE 안내).
-- DEFAULT가 백필을 대신하므로 보정 UPDATE를 동봉하지 않는다.
-- 롤링 안전: 신규 컬럼 + DEFAULT라 구버전 코드의 INSERT(user_id, brand_id, username)에 기본값이 먹는다.
ALTER TABLE app.brand_monitorings
    ADD COLUMN account_type text NOT NULL DEFAULT 'own';

-- 값 공간은 정확히 둘. 애플리케이션 검증(BrandAccountType)의 최후 보루다.
ALTER TABLE app.brand_monitorings
    ADD CONSTRAINT brand_monitorings_account_type_chk
    CHECK (account_type IN ('own', 'competitor'));
