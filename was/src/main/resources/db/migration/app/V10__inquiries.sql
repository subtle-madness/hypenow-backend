-- 도입문의(클로즈베타 설계 2026-07-19) — 코드 없는 방문자의 문의 접수, 운영자가 확인 후 수동 코드 발송.
-- id는 uuid — 공개 엔드포인트 응답에 노출되므로 순번(문의 수) 노출을 피한다(app 유일한 uuid PK 예외).
-- organization은 유형별 소속명(브랜드명/대행사명/유통사명/계정명) — 가입의 company_name과 동일한 해석.
CREATE TABLE app.inquiries (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_type    text NOT NULL CHECK (user_type IN ('brand', 'agency', 'distributor', 'influencer')),
    name         text NOT NULL,
    email        text NOT NULL,                  -- 회신용 — 가입 이메일과 무관, 정규화·유일 제약 없음
    organization text NOT NULL,
    message      text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
