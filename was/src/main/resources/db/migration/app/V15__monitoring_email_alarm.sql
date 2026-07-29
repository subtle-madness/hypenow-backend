-- 모니터링 이메일 알람 저장 계층(스펙 2026-07-29 §2).
-- 옵트아웃: 행 없음 = 알림 on(기본) — 설정 화면(이벤트 4종 × 이메일 토글)의 저장소.
-- 토글 API는 프론트 /v1 작업 때 — 지금은 크론이 읽기만 한다.
CREATE TABLE app.monitoring_email_opt_outs (
    user_id    bigint NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    event_type text   NOT NULL CHECK (event_type IN
                      ('POST_DETECTED', 'POST_HIDDEN', 'UPLOAD_MISSED', 'MONITORING_ENDED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, event_type)
);

-- 발송 워터마크: 이벤트별 1행 — 중복 발송 방지는 전적으로 was 책임(계약 §4).
CREATE TABLE app.monitoring_alarm_state (
    event_type       text PRIMARY KEY,
    last_notified_at timestamptz NOT NULL
);

-- 시드: 마이그레이션 시각부터 시작 — 적용 이전 감지분의 일괄 발송 방지.
INSERT INTO app.monitoring_alarm_state (event_type, last_notified_at)
VALUES ('POST_DETECTED', now())
ON CONFLICT DO NOTHING;
