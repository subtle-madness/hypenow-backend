-- was 런타임 설정 key-value (raw DB app_setting의 was층 대응 — was는 raw 접근 금지라 별도 소유).
-- 첫 사용: signup.code (가입 코드 — 로그인 월 설계 2026-07-17).
-- 빈 값 시드 = 가입 전면 차단(fail-closed). 개통은 운영자가 직접:
--   UPDATE app.app_setting SET value = '<실코드>' WHERE key = 'signup.code';
CREATE TABLE app.app_setting (
    key   text PRIMARY KEY,
    value text NOT NULL
);

INSERT INTO app.app_setting (key, value) VALUES ('signup.code', '');
