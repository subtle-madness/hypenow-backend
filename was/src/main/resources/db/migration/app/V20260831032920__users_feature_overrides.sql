-- 유저별 기능 플래그(2026-08-31) — 프론트가 소유하는 기능 키의 유저 단위 덮어쓰기 값을 담는다.
-- 값 타입은 boolean | string[]만 허용하지만 검증은 was가 하고(키 문자열은 검증하지 않는다),
-- 스키마는 자유 형식 jsonb 객체로 둔다 — 기능 목록·기본값의 정본이 프론트라 DB가 어휘를 고정하면
-- 기능이 늘 때마다 마이그레이션이 따라붙는다.
--
-- expand-only: 신규 컬럼 1개뿐이라 롤링 배포 중 구 코드는 이 컬럼을 모른 채 그대로 동작한다.
-- NOT NULL DEFAULT '{}'는 "미설정"을 null과 빈 객체 두 값으로 쪼개지 않기 위한 것 —
-- 응답 계약(featureOverrides는 항상 객체, null 금지)이 저장 계층에서부터 성립한다.
-- Postgres 11+에서 DEFAULT 있는 ADD COLUMN은 기존 행 재작성 없이 메타데이터만 바꾼다.
ALTER TABLE app.users ADD COLUMN IF NOT EXISTS feature_overrides jsonb NOT NULL DEFAULT '{}'::jsonb;
