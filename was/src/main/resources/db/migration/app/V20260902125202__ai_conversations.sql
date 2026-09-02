-- 브랜드 모니터링 AI 어시스턴트 대화 영속화(FE 변경요청서 2026-08-28 §8) - expand-only.
--
-- 지금까지 챗은 무상태였다(app.ai_chat_logs는 질문 1건당 1행, 대화로 묶이지 않았다). 프론트가
-- 대화 목록·이어보기·삭제를 요구해 대화 단위 컨테이너(app.ai_conversations)를 추가하고, 기존 로그
-- 테이블에 conversation_id 연결 컬럼을 단다. 로그 테이블 자체는 그대로 append-only 원장으로 남는다
-- (질문 1건 = 로그 1행이라는 기존 계약은 유지 - 대화는 로그를 묶는 상위 개념일 뿐이다).
--
-- soft delete(deleted_at)를 쓰는 이유: 대화 삭제는 사용자 조작이라 흔하고, 로그(app.ai_chat_logs)는
-- 삭제된 대화 아래에서도 수요 신호로 남아야 한다(하드 삭제하면 conversation_id FK가 깨진다).
CREATE TABLE app.ai_conversations (
    id          bigserial   PRIMARY KEY,
    user_id     bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    -- monitoring brand_account.id 논리 참조 - 크로스 DB FK 금지(app.ai_chat_logs.brand_id와 동일 관용구).
    brand_id    bigint      NOT NULL,
    -- 첫 사용자 발화를 200자로 절단해 담는다(목록 화면의 대화 제목).
    title       text        NOT NULL,
    deleted_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- 대화 목록(유저·브랜드 스코프, 최신순)이 유일한 뜨거운 조회 경로다. 삭제된 대화는 부분 인덱스로 제외.
CREATE INDEX ai_conversations_user_brand_updated_idx
    ON app.ai_conversations (user_id, brand_id, updated_at DESC)
    WHERE deleted_at IS NULL;

-- 로그를 대화로 묶는 연결 컬럼 - nullable이다(대화 없이 던진 기존 질문·향후에도 대화 미연결 질문을
-- 허용할 수 있게). preset_id·scope·follow_ups·refs는 프론트 변경요청서 §8의 대화 상세 응답에 실을
-- 필드들을 로그 적재 시점에 함께 남긴다. references는 SQL 예약어라 컬럼명은 refs로 피한다.
ALTER TABLE app.ai_chat_logs
    ADD COLUMN conversation_id bigint REFERENCES app.ai_conversations(id),
    ADD COLUMN preset_id       text,
    ADD COLUMN scope           jsonb,
    ADD COLUMN follow_ups      jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN refs            jsonb NOT NULL DEFAULT '[]'::jsonb;

-- 대화 상세 조회(conversation_id 스코프, 시간순)의 인덱스. 대화에 안 묶인 행(NULL)은 이 조회에
-- 애초에 안 걸리므로 부분 인덱스로 제외.
CREATE INDEX ai_chat_logs_conversation_created_idx
    ON app.ai_chat_logs (conversation_id, created_at)
    WHERE conversation_id IS NOT NULL;

-- 분당 질문 상한 기준값(FE 변경요청서 §9.1) - 기존 ai.chat.daily-limit과 같은 시드 관용구
-- (07-20 수동 등록분 유실 사고 후 확립된 규칙). 런타임 조정은 이 행 UPDATE로만 한다.
INSERT INTO app.app_setting (key, value) VALUES ('ai.chat.per-minute-limit', '5')
ON CONFLICT (key) DO NOTHING;
