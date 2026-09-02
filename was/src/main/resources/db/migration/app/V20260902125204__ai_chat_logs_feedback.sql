-- 브랜드 AI 챗 답변 피드백(👍👎) 저장 컬럼(2026-09-02) - 골드셋 발굴 필터 목적(스펙 2026-09-01 §2·§7-3).
--
-- app.ai_chat_logs는 append-only 원장이지만(V20260902125201 참고), 피드백은 사용자가 나중에
-- 답변을 보고 남기는 부가 정보라 예외적으로 UPDATE 대상이다 - 그래서 별도 리포지토리
-- (AiChatFeedbackRepository)로 갱신 경로를 분리했다(로그 적재 리포지토리는 여전히 append-only).
-- expand 단계: 컬럼 추가만이라 롤링 배포 중 구버전 코드가 이 테이블을 계속 봐도 안전하다.
ALTER TABLE app.ai_chat_logs
    ADD COLUMN feedback         text CHECK (feedback IN ('up', 'down')),
    ADD COLUMN feedback_comment text,
    ADD COLUMN feedback_at      timestamptz;
