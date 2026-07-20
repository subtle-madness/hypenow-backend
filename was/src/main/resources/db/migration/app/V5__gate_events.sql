-- 게이트/잠금 클릭 측정 이벤트 (스펙 6.19) — fire-and-forget 기록 전용.
CREATE TABLE app.gate_events (
    id         bigserial PRIMARY KEY,
    user_id    bigint,                          -- 익명 허용 (users 논리 참조 — 탈퇴 후에도 이벤트 보존)
    event_type text NOT NULL,
    payload    jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gate_events_ix1 ON app.gate_events (event_type, created_at);
