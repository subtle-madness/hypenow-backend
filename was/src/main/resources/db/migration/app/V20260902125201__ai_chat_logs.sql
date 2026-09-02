-- 브랜드 모니터링 AI 어시스턴트 PoC 질문 로그(2026-08-27 설계 §6) - append-only.
--
-- PoC가 검증하려는 "사용자가 무엇을 묻는가"의 정본이다. 질문만이 아니라 답변과 툴 시퀀스까지
-- 남기는 이유: "이 질문에 모델이 어떤 툴을 골랐고 몇 건을 받았나"가 다음 툴 백로그의 근거라서다.
-- 일일 질문 상한(설계 §7)도 별도 카운터 테이블 없이 이 테이블을 센다 - 로그가 곧 원장이다.
--
-- tool_calls는 [{"name": "list_posts", "args": {...}, "rows": 3}] 형태 jsonb 배열
-- (배열 저장은 text[] 대신 jsonb - CLAUDE.md 컨벤션).
CREATE TABLE app.ai_chat_logs (
    id            bigserial   PRIMARY KEY,
    user_id       bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    -- 모델이 실제로 조회한 브랜드(툴 인자에서 관측). 브랜드가 특정되지 않은 질문은 NULL.
    -- monitoring brand_account.id 논리 참조 - 크로스 DB FK 금지(app.brand_monitorings와 동일 규칙).
    brand_id      bigint,
    question      text        NOT NULL,
    -- LLM 실패로 답을 못 만든 경우 NULL. 실패한 질문도 수요 신호라 행 자체는 남긴다.
    answer        text,
    tool_calls    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    prompt_tokens integer     NOT NULL DEFAULT 0,
    output_tokens integer     NOT NULL DEFAULT 0,
    elapsed_ms    integer     NOT NULL DEFAULT 0,
    -- ok | tool_cap | llm_call_cap | llm_failed | blocked. 값 공간은 AiChatLogEntry의 OUTCOME_* 상수가 정본.
    outcome       text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- 일일 상한 판정(user_id + created_at 범위 count)이 유일한 뜨거운 조회 경로다.
CREATE INDEX ai_chat_logs_user_created_idx ON app.ai_chat_logs (user_id, created_at DESC);

-- 유저당 일일 질문 상한 기준값(설계 §7). 기준값은 마이그레이션으로 시드하고 런타임 조정만
-- app_setting UPDATE로 한다(07-20 수동 등록분 유실 사고 후 확립된 규칙).
INSERT INTO app.app_setting (key, value) VALUES ('ai.chat.daily-limit', '30')
ON CONFLICT (key) DO NOTHING;
