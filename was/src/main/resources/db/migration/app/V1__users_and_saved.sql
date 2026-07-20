-- 서비스 데이터 (app 스키마 — was 소유, 분석 결과와 FK·조인 없음 §4-4)
CREATE TABLE users (
    id            bigserial PRIMARY KEY,
    email         text NOT NULL UNIQUE,          -- Java에서 lower 정규화 후 저장
    password_hash text NOT NULL,                 -- BCrypt
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- 인플루언서 후보 (상태 어휘는 was가 확정: 검토중/컨택 예정/협업 중)
CREATE TABLE saved_influencers (
    user_id    bigint NOT NULL REFERENCES users(id),
    handle     text   NOT NULL,                  -- accounts.handle 논리 참조 (FK 금지)
    status     text   NOT NULL DEFAULT 'reviewing'
               CHECK (status IN ('reviewing', 'contact_planned', 'collaborating')),
    memo       text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, handle)
);

-- 콘텐츠 북마크
CREATE TABLE saved_contents (
    user_id    bigint NOT NULL REFERENCES users(id),
    short_code text   NOT NULL,                  -- contents.short_code 논리 참조
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, short_code)
);
