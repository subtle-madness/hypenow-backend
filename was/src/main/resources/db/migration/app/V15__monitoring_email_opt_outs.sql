-- 모니터링 알람 이메일 옵트아웃 — **행 없음 = 켜짐**(기본 on). 설정 화면과 1:1이라 빈 테이블이 곧 전원 수신.
-- 읽기는 monitoring 알람 모듈(읽기 전용 롤 alarm_reader), 쓰기(토글 API)는 was 소유 — 계약 v2 §6.
-- event_type 어휘의 정본은 monitoring의 AlarmEventType이다(alarm_event.event_type CHECK와 같은 목록).
CREATE TABLE app.monitoring_email_opt_outs (
    user_id    bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    event_type text        NOT NULL CHECK (event_type IN
               ('COLLECTION_STARTED','COLLECTION_ENDED','METRICS_HIDDEN','CONTENT_UNAVAILABLE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_type)
);
