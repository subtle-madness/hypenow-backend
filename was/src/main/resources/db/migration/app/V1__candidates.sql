-- 후보 관리 (태스크 G): 마케터가 저장한 인플루언서 후보 + 상태 + 메모.
-- handle은 분석 결과(account_summaries 등)와 논리 참조만 — FK 금지 (ARCHITECTURE §4-4, 물리 분리 대비).
-- 로그인 도입 시 owner_id 추가 + UNIQUE(owner_id, handle) 전환을 위해 surrogate PK를 둔다.
CREATE TABLE app.candidates (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    handle     text NOT NULL UNIQUE,
    status     text NOT NULL DEFAULT 'REVIEWING'
               CHECK (status IN ('REVIEWING', 'CONTACT_PLANNED', 'COLLABORATING')),
    memo       text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
