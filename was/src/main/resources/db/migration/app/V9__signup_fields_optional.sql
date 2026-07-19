-- 가입 필드 경량화(클로즈베타 전환 2026-07-19) — 관문(코드) 통과 후 마찰 최소화.
-- 선택 필드는 NULL 허용 + 기본값 제거(신규 가입 미입력은 NULL로 저장 — V3 백필 기본값과 구분).
-- CHECK 제약은 NULL을 통과시키므로 그대로 두고, 값이 있을 때의 어휘 검사로 계속 동작한다.
-- company_name은 필수 유지 — 유형별 소속명(브랜드명/유통사명/대행사명/계정명)으로 의미만 확장.
ALTER TABLE app.users
    ALTER COLUMN signup_route       DROP NOT NULL,
    ALTER COLUMN signup_route       DROP DEFAULT,
    ALTER COLUMN phone_country_code DROP NOT NULL,
    ALTER COLUMN phone_country_code DROP DEFAULT,
    ALTER COLUMN phone_number       DROP NOT NULL,
    ALTER COLUMN phone_number       DROP DEFAULT,
    ALTER COLUMN company_size       DROP NOT NULL,
    ALTER COLUMN company_size       DROP DEFAULT,
    ALTER COLUMN industry           DROP NOT NULL,
    ALTER COLUMN industry           DROP DEFAULT,
    ALTER COLUMN job_title          DROP NOT NULL,
    ALTER COLUMN job_title          DROP DEFAULT,
    ADD COLUMN usage_purpose text;  -- 활용 목적(대행사 선택 입력, 2026-07-19)
