-- super 초대코드(설계 2026-08-04) — is_super=true인 코드는 인원 제한 없이 가입 가능.
-- 소진 스탬프(used_at)를 영원히 찍지 않는 방식이라 기존 "used_at IS NULL = 미소진" 정본과 공존한다.
-- 컬럼 추가만이라 expand-contract 안전(구 코드는 컬럼 무시, 기존 1회용 동작 유지).
ALTER TABLE app.signup_codes ADD COLUMN is_super boolean NOT NULL DEFAULT false;
