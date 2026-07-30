-- 등록 처리 내역 확인(읽음) 상태(프론트 요청) — 프론트가 localStorage로 임시 처리하던 확인 상태를
-- 서버로 옮긴다. 알림 다이제스트 read_at(V15)과 동일한 패턴(멱등 읽음 처리, 유저 스코프).
-- nullable 확장 — 이 마이그레이션 이전 행은 null(미확인)로 남는다.
ALTER TABLE app.monitoring_registrations
    ADD COLUMN acknowledged_at timestamptz;
